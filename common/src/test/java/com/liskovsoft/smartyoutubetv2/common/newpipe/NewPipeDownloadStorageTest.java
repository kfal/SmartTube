package com.liskovsoft.smartyoutubetv2.common.newpipe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import androidx.work.Data;
import androidx.work.WorkInfo;
import androidx.work.testing.TestWorkerBuilder;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowStorageManager;
import org.robolectric.shadows.StorageVolumeBuilder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NewPipeDownloadStorageTest {
    private static final String USB_ID = "ABCD-1234";
    private static final String AUTHORITY = "com.android.externalstorage.documents";
    @Rule
    public TemporaryFolder files = new TemporaryFolder();
    private Context context;
    private ShadowStorageManager storage;
    private StorageVolume usb;
    private TestDocumentsProvider provider;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        storage = shadowOf((StorageManager) context.getSystemService(Context.STORAGE_SERVICE));
        storage.resetStorageVolumeList();
        usb = removable(USB_ID, "USB drive", Environment.MEDIA_MOUNTED);
        storage.addStorageVolume(usb);
        provider = new TestDocumentsProvider(files.getRoot());
        ProviderInfo info = new ProviderInfo();
        info.authority = AUTHORITY;
        info.exported = true;
        info.grantUriPermissions = true;
        info.readPermission = "android.permission.MANAGE_DOCUMENTS";
        info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider);
    }

    @Test
    public void prefersUsbOverInternalAndOtherRemovableStorage() {
        storage.resetStorageVolumeList();
        storage.addStorageVolume(new StorageVolumeBuilder("primary", new File("/storage/emulated/0"),
                "Internal storage", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
                .setIsPrimary(true).setIsEmulated(true).build());
        storage.addStorageVolume(removable("AAAA-0000", "SD card", Environment.MEDIA_MOUNTED));
        storage.addStorageVolume(usb);
        assertSame(usb, NewPipeDownloadStorage.preferredVolume(context));
    }

    @Test
    public void keepsInternalDestinationWhenNoRemovableDriveIsMounted() throws Exception {
        storage.resetStorageVolumeList();
        storage.addStorageVolume(removable(USB_ID, "USB drive", Environment.MEDIA_UNMOUNTED));
        assertNull(NewPipeDownloadStorage.preferredVolume(context));
        Data destination = NewPipeDownloadStorage.destination(null, null);
        assertNull(NewPipeDownloadStorage.prepareUsbDirectory(context, destination));
        assertEquals("Download/SmartTube", NewPipeDownloadStorage.location(destination));
    }

    @Test
    @Config(sdk = {24, 32})
    public void createsMissingFoldersReusesThemAndWritesToUsb() throws Exception {
        Data destination = authorizedDestination(USB_ID + ":");
        Uri directory = NewPipeDownloadStorage.prepareUsbDirectory(context, destination);
        assertEquals(USB_ID + ":Download/SmartTube", DocumentsContract.getDocumentId(directory));
        assertEquals(directory, NewPipeDownloadStorage.prepareUsbDirectory(context, destination));
        File source = files.newFile();
        Files.write(source.toPath(), "downloaded-media".getBytes(StandardCharsets.UTF_8));
        String path = worker().publish(source, "clip.mp4", "video/mp4", directory);
        assertEquals(USB_ID + ":/Download/SmartTube/clip.mp4", path);
        assertEquals("downloaded-media", new String(Files.readAllBytes(
                provider.documents.get(USB_ID + ":Download/SmartTube/clip.mp4").file.toPath()), StandardCharsets.UTF_8));
        assertEquals(Arrays.asList("Download", "SmartTube", "clip.mp4"), provider.createdNames);
    }

    @Test
    public void doesNotNestAnotherFolderInsideSelectedSmartTubeFolder() throws Exception {
        provider.createDocument(USB_ID + ":", DocumentsContract.Document.MIME_TYPE_DIR, "SmartTube");
        Data destination = authorizedDestination(USB_ID + ":SmartTube");
        Uri directory = NewPipeDownloadStorage.prepareUsbDirectory(context, destination);
        assertEquals(USB_ID + ":SmartTube", DocumentsContract.getDocumentId(directory));
        assertEquals(Arrays.asList("SmartTube"), provider.createdNames);
    }

    @Test
    public void failsExplicitlyWhenAFileOccupiesTheDownloadFolder() throws Exception {
        provider.createDocument(USB_ID + ":", "video/mp4", "Download");
        assertStorageRejected(authorizedDestination(USB_ID + ":"));
        assertEquals(Arrays.asList("Download"), provider.createdNames);
    }

    @Test
    public void disconnectedOrReadOnlyUsbNeverFallsBackToInternal() throws Exception {
        Data destination = authorizedDestination(USB_ID + ":");
        for (String state : Arrays.asList(Environment.MEDIA_UNMOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)) {
            storage.resetStorageVolumeList();
            storage.addStorageVolume(removable(USB_ID, "USB drive", state));
            assertStorageRejected(destination);
        }
        assertTrue(provider.createdNames.isEmpty());
    }

    @Test
    public void rejectsWrongDriveAndRevokedFolderAccess() throws Exception {
        Uri wrongTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "OTHER-USB:");
        grant(wrongTree);
        assertStorageRejected(NewPipeDownloadStorage.destination(usb, wrongTree));
        Uri correctTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, USB_ID + ":");
        assertStorageRejected(NewPipeDownloadStorage.destination(usb, correctTree));
        assertTrue(provider.createdNames.isEmpty());
    }

    @Test
    public void removesIncompleteUsbFileWhenCancelled() throws Exception {
        Uri directory = NewPipeDownloadStorage.prepareUsbDirectory(context, authorizedDestination(USB_ID + ":"));
        File source = files.newFile();
        Files.write(source.toPath(), "media".getBytes(StandardCharsets.UTF_8));
        NewPipeDownloadWorker worker = worker();
        worker.stop(WorkInfo.STOP_REASON_CANCELLED_BY_APP);
        try {
            worker.publish(source, "cancelled.mp4", "video/mp4", directory);
            fail("Expected cancellation to fail publication");
        } catch (IOException expected) {
            assertFalse(provider.documents.containsKey(USB_ID + ":Download/SmartTube/cancelled.mp4"));
            assertEquals(1, provider.deletedFiles);
        }
    }

    @Test
    @Config(sdk = 32)
    public void modernFolderPickerStartsOnTheSelectedUsbVolume() {
        Intent intent = NewPipeDownloadStorage.accessIntent(usb);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.getAction());
        Uri initial = intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI);
        assertNotNull(initial);
        assertTrue(initial.toString().contains(USB_ID));
    }

    private void assertStorageRejected(Data destination) throws Exception {
        try {
            NewPipeDownloadStorage.prepareUsbDirectory(context, destination);
            fail("Expected inaccessible USB storage to fail, not redirect the download");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private Data authorizedDestination(String documentId) {
        Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, documentId);
        grant(tree);
        return NewPipeDownloadStorage.destination(usb, tree);
    }

    private void grant(Uri tree) {
        context.getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    private StorageVolume removable(String uuid, String description, String state) {
        return new StorageVolumeBuilder(uuid, new File("/storage/" + uuid), description, Process.myUserHandle(), state)
                .setIsPrimary(false).setIsRemovable(true).setFsUuid(uuid).build();
    }

    private NewPipeDownloadWorker worker() {
        return TestWorkerBuilder.from(context, NewPipeDownloadWorker.class, Runnable::run).build();
    }

    private static class TestDocumentsProvider extends DocumentsProvider {
        final Map<String, Document> documents = new LinkedHashMap<>();
        final List<String> createdNames = new ArrayList<>();
        final File directory;
        int deletedFiles;

        TestDocumentsProvider(File directory) {
            this.directory = directory;
            documents.put(USB_ID + ":", new Document(null, "USB drive", DocumentsContract.Document.MIME_TYPE_DIR, null));
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor queryRoots(String[] projection) {
            return new MatrixCursor(projection == null ? new String[] {DocumentsContract.Root.COLUMN_ROOT_ID} : projection);
        }

        @Override
        public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
            MatrixCursor cursor = cursor(projection);
            addRow(cursor, documentId);
            return cursor;
        }

        @Override
        public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
                throws FileNotFoundException {
            MatrixCursor cursor = cursor(projection);
            for (Map.Entry<String, Document> entry : documents.entrySet()) {
                if (parentDocumentId.equals(entry.getValue().parent)) {
                    addRow(cursor, entry.getKey());
                }
            }
            return cursor;
        }

        private MatrixCursor cursor(String[] projection) {
            return new MatrixCursor(projection == null ? new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE} : projection);
        }

        private void addRow(MatrixCursor cursor, String id) throws FileNotFoundException {
            Document document = documents.get(id);
            if (document == null) {
                throw new FileNotFoundException(id);
            }
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : cursor.getColumnNames()) {
                switch (column) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row.add(document.name); break;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE: row.add(document.mime); break;
                    default: row.add(null);
                }
            }
        }

        @Override
        public String createDocument(String parentDocumentId, String mimeType, String displayName) {
            String id = parentDocumentId + (parentDocumentId.endsWith(":") ? "" : "/") + displayName;
            documents.put(id, new Document(parentDocumentId, displayName, mimeType, new File(directory, "usb-" + documents.size())));
            createdNames.add(displayName);
            return id;
        }

        @Override
        public boolean isChildDocument(String parentDocumentId, String documentId) {
            return documentId.startsWith(parentDocumentId);
        }

        @Override
        public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
                throws FileNotFoundException {
            return ParcelFileDescriptor.open(documents.get(documentId).file, ParcelFileDescriptor.parseMode(mode));
        }

        @Override
        public void deleteDocument(String documentId) throws FileNotFoundException {
            Document document = documents.get(documentId);
            if (document.file.exists() && !document.file.delete()) {
                throw new FileNotFoundException("Cannot delete " + documentId);
            }
            documents.remove(documentId);
            deletedFiles++;
        }
    }

    private static class Document {
        final String parent;
        final String name;
        final String mime;
        final File file;

        Document(String parent, String name, String mime, File file) {
            this.parent = parent;
            this.name = name;
            this.mime = mime;
            this.file = file;
        }
    }
}
