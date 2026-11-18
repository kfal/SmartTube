package com.liskovsoft.smartyoutubetv2.common.newpipe;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import androidx.work.Data;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;

import java.io.IOException;
import java.util.Locale;

final class NewPipeDownloadStorage {
    static final String VOLUME = "download_volume";
    static final String TREE = "download_tree";
    private static final String PRIMARY = "external_primary";
    private static final String FOLDER = "SmartTube";
    private static final String AUTHORITY = "com.android.externalstorage.documents";
    private static final String TREE_PREF = "newpipe_usb_tree_";

    private NewPipeDownloadStorage() {}

    static StorageVolume preferredVolume(Context context) {
        StorageVolume first = null;
        for (StorageVolume volume : manager(context).getStorageVolumes()) {
            String state = volume.getState();
            if (volume.isPrimary() || !volume.isRemovable() || volume.getUuid() == null ||
                    (!Environment.MEDIA_MOUNTED.equals(state) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state))) {
                continue;
            }
            if (volume.getDescription(context).toLowerCase(Locale.ROOT).contains("usb")) {
                return volume;
            }
            if (first == null) {
                first = volume;
            }
        }
        return first;
    }

    static void requireMounted(Context context, String volumeId) throws IOException {
        for (StorageVolume volume : manager(context).getStorageVolumes()) {
            if (!volume.isPrimary() && volumeId.equalsIgnoreCase(volume.getUuid()) &&
                    Environment.MEDIA_MOUNTED.equals(volume.getState())) {
                return;
            }
        }
        throw new IOException(context.getString(R.string.newpipe_usb_unavailable, volumeId));
    }

    private static StorageManager manager(Context context) {
        StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("Android storage service is unavailable");
        }
        return manager;
    }

    static Intent accessIntent(StorageVolume volume) {
        Intent intent = Build.VERSION.SDK_INT >= 29 ? volume.createOpenDocumentTreeIntent() :
                volume.createAccessIntent(Environment.DIRECTORY_DOWNLOADS);
        if (intent == null) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        }
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }

    static Uri savedTree(Context context, String volumeId) {
        String saved = AppPrefs.instance(context).getData(TREE_PREF + volumeId);
        if (saved == null) {
            return null;
        }
        Uri tree = Uri.parse(saved);
        return hasTreeGrant(context, tree) ? tree : null;
    }

    static void saveTree(Context context, String volumeId, Uri tree, int flags) throws IOException {
        requireMounted(context, volumeId);
        validateTree(context, volumeId, tree);
        int grants = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (grants != (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            throw new IOException(context.getString(R.string.newpipe_usb_access_denied));
        }
        context.getContentResolver().takePersistableUriPermission(tree, grants);
        AppPrefs.instance(context).setData(TREE_PREF + volumeId, tree.toString());
    }

    static Data destination(StorageVolume volume, Uri tree) {
        Data.Builder data = new Data.Builder().putString(VOLUME, volume == null ? PRIMARY : volume.getUuid());
        if (tree != null) {
            data.putString(TREE, tree.toString());
        }
        return data.build();
    }

    static Uri prepareUsbDirectory(Context context, Data destination) throws IOException {
        String volumeId = destination.getString(VOLUME);
        // Jobs queued by earlier versions retain their original internal destination.
        if (volumeId == null || PRIMARY.equals(volumeId)) {
            return null;
        }
        requireMounted(context, volumeId);
        String value = destination.getString(TREE);
        Uri tree = value == null ? null : Uri.parse(value);
        validateTree(context, volumeId, tree);
        if (!hasTreeGrant(context, tree)) {
            throw new IOException(context.getString(R.string.newpipe_usb_access_denied));
        }
        String documentId = DocumentsContract.getTreeDocumentId(tree);
        String path = documentId.substring(documentId.indexOf(':') + 1);
        Uri directory = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
        if (path.isEmpty()) {
            directory = ensureDirectory(context, directory, Environment.DIRECTORY_DOWNLOADS);
        }
        if (!isAppFolder(path)) {
            directory = ensureDirectory(context, directory, FOLDER);
        }
        return directory;
    }

    private static boolean hasTreeGrant(Context context, Uri tree) {
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(tree) && permission.isReadPermission() && permission.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    private static void validateTree(Context context, String volumeId, Uri tree) throws IOException {
        if (tree == null || !AUTHORITY.equals(tree.getAuthority()) || !DocumentsContract.isTreeUri(tree) ||
                !DocumentsContract.getTreeDocumentId(tree).regionMatches(true, 0, volumeId + ":", 0, volumeId.length() + 1)) {
            throw new IOException(context.getString(R.string.newpipe_usb_wrong_volume));
        }
    }

    private static Uri ensureDirectory(Context context, Uri parent, String name) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent));
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = Build.VERSION.SDK_INT >= 26 ?
                context.getContentResolver().query(children, columns, null, null) :
                context.getContentResolver().query(children, columns, null, null, null)) {
            if (cursor == null) {
                throw new IOException("Cannot read USB download directory");
            }
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) {
                    if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                        throw new IOException("USB download folder is occupied by a file: " + name);
                    }
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0));
                }
            }
        }
        Uri created = DocumentsContract.createDocument(context.getContentResolver(), parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) {
            throw new IOException("Cannot create USB download folder: " + name);
        }
        return created;
    }

    static String location(Data destination) {
        String volumeId = destination.getString(VOLUME);
        if (volumeId == null || PRIMARY.equals(volumeId)) {
            return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER;
        }
        String documentId = DocumentsContract.getTreeDocumentId(Uri.parse(destination.getString(TREE)));
        String path = documentId.substring(documentId.indexOf(':') + 1);
        if (path.isEmpty()) {
            path = Environment.DIRECTORY_DOWNLOADS;
        }
        return volumeId + ":/" + (isAppFolder(path) ? path : path + "/" + FOLDER);
    }

    private static boolean isAppFolder(String path) {
        return FOLDER.equals(path) || path.endsWith("/" + FOLDER);
    }
}
