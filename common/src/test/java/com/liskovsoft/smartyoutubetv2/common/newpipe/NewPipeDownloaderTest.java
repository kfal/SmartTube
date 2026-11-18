package com.liskovsoft.smartyoutubetv2.common.newpipe;

import org.junit.Test;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class NewPipeDownloaderTest {
    @Test
    public void preservesExtractionRequestAndResponseButNeverForwardsAccountCredentials() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"ok\":true}").addHeader("Content-Type", "application/json"));
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("X-Youtube-Client-Name", Collections.singletonList("1"));
            headers.put("Content-Type", Collections.singletonList("application/json"));
            headers.put("Cookie", Arrays.asList("SID=account", "SAPISID=account"));
            headers.put("Authorization", Collections.singletonList("Bearer account"));
            Request request = new Request("POST", server.url("/player").toString(), headers,
                    "{}".getBytes(StandardCharsets.UTF_8), null, false);

            Response response = new NewPipeService.AnonymousDownloader().execute(request);
            RecordedRequest sent = server.takeRequest();
            assertEquals("POST", sent.getMethod());
            assertEquals("{}", sent.getBody().readUtf8());
            assertEquals("1", sent.getHeader("X-Youtube-Client-Name"));
            assertNull(sent.getHeader("Cookie"));
            assertNull(sent.getHeader("Authorization"));
            assertNotNull(sent.getHeader("User-Agent"));
            assertEquals(200, response.responseCode());
            assertEquals("{\"ok\":true}", response.responseBody());
        }
    }

    @Test(expected = ReCaptchaException.class)
    public void reportsRateLimitingInsteadOfReturningAnEmptySuccess() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(429));
            Request request = new Request("GET", server.url("/watch").toString(),
                    Collections.emptyMap(), null, null, false);
            new NewPipeService.AnonymousDownloader().execute(request);
        }
    }
}
