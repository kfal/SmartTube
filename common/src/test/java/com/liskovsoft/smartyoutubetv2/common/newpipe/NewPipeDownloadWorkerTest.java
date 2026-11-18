package com.liskovsoft.smartyoutubetv2.common.newpipe;

import androidx.work.testing.TestWorkerBuilder;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NewPipeDownloadWorkerTest {
    @Rule
    public TemporaryFolder files = new TemporaryFolder();

    @Test
    public void joinsRangesWithoutRepeatingOrDroppingBytes() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-2/6").setBody("abc"));
            server.enqueue(new MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 3-5/6").setBody("def"));
            File destination = files.newFile();
            worker().download(server.url("/media").toString(), destination);
            assertEquals("abcdef", new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
            assertEquals("bytes=0-10485759", server.takeRequest().getHeader("Range"));
            assertEquals("bytes=3-10485762", server.takeRequest().getHeader("Range"));
        }
    }

    @Test
    public void acceptsACompleteResponseWhenServerIgnoresFirstRange() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("complete"));
            File destination = files.newFile();
            worker().download(server.url("/media").toString(), destination);
            assertEquals("complete", new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void rejectsTruncatedRangeInsteadOfSavingAnIncompleteFile() throws Exception {
        assertRejected(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-5/6").setBody("abc"));
    }

    @Test
    public void rejectsUnexpectedOffsetInsteadOfCorruptingTheFile() throws Exception {
        assertRejected(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 1-3/4").setBody("abc"));
    }

    private void assertRejected(MockResponse response) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(response);
            try {
                worker().download(server.url("/media").toString(), files.newFile());
                fail("Expected an invalid transfer to fail");
            } catch (IOException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    private NewPipeDownloadWorker worker() {
        return TestWorkerBuilder.from(RuntimeEnvironment.getApplication(),
                NewPipeDownloadWorker.class, Runnable::run).build();
    }
}
