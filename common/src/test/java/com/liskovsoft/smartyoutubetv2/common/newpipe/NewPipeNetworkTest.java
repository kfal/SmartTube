package com.liskovsoft.smartyoutubetv2.common.newpipe;

import org.junit.Assume;
import org.junit.Test;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.InputStream;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class NewPipeNetworkTest {
    @Test
    public void extractsPublicVideoWithoutLoginAndReadsMediaBytes() throws Exception {
        Assume.assumeTrue("Set SMARTTUBE_NEWPIPE_NETWORK_TEST=1 for the live YouTube check",
                "1".equals(System.getenv("SMARTTUBE_NEWPIPE_NETWORK_TEST")));
        StreamInfo info = NewPipeService.extract("aqz-KE-bpKQ");
        assertNotNull(info.getName());
        List<NewPipeService.DownloadChoice> choices = NewPipeService.downloadChoices(info);
        assertFalse("No downloadable media returned by YouTube", choices.isEmpty());
        NewPipeService.DownloadChoice choice = choices.get(0);
        verifyMedia(choice.stream.getContent());
        if (choice.audio != null) {
            verifyMedia(choice.audio.getContent());
        }
    }

    private static void verifyMedia(String url) throws Exception {
        Request request = new Request.Builder().url(url).header("User-Agent", NewPipeService.USER_AGENT)
                .header("Range", "bytes=0-1023").build();
        try (Response response = NewPipeService.getClient().newCall(request).execute()) {
            assertTrue("Media HTTP " + response.code(), response.isSuccessful());
            assertNotNull(response.body());
            try (InputStream input = response.body().byteStream()) {
                assertNotEquals(-1, input.read());
            }
        }
    }
}
