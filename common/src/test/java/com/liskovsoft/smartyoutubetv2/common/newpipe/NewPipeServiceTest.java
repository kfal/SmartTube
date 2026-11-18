package com.liskovsoft.smartyoutubetv2.common.newpipe;

import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NewPipeServiceTest {
    @Test
    public void adaptiveVideoRequiresAudioAndIsNotOfferedSilentlyWithoutSound() {
        StreamInfo info = info(StreamType.VIDEO_STREAM);
        VideoStream video = video("137", "1080p", true, DeliveryMethod.PROGRESSIVE_HTTP);
        info.setVideoOnlyStreams(Collections.singletonList(video));
        assertTrue(NewPipeService.downloadChoices(info).isEmpty());

        AudioStream audio = audio("140", 128);
        info.setAudioStreams(Collections.singletonList(audio));
        List<NewPipeService.DownloadChoice> choices = NewPipeService.downloadChoices(info);
        assertEquals(2, choices.size());
        assertEquals(video, choices.get(0).stream);
        assertEquals(audio, choices.get(0).audio);
        assertEquals("mp4", choices.get(0).extension);
        assertEquals("m4a", choices.get(1).extension);
    }

    @Test
    public void muxedVideoDoesNotGetAnExtraAudioTrack() {
        StreamInfo info = info(StreamType.VIDEO_STREAM);
        info.setVideoStreams(Collections.singletonList(video("18", "360p", false, DeliveryMethod.PROGRESSIVE_HTTP)));
        List<NewPipeService.DownloadChoice> choices = NewPipeService.downloadChoices(info);
        assertEquals(1, choices.size());
        assertNull(choices.get(0).audio);
    }

    @Test
    public void sortsResolutionAndUsesHighestBitrateAudio() {
        StreamInfo info = info(StreamType.VIDEO_STREAM);
        info.setVideoOnlyStreams(Arrays.asList(video("136", "720p", true, DeliveryMethod.PROGRESSIVE_HTTP),
                video("137", "1080p60", true, DeliveryMethod.PROGRESSIVE_HTTP)));
        info.setAudioStreams(Arrays.asList(audio("139", 48), audio("140", 128)));
        List<NewPipeService.DownloadChoice> choices = NewPipeService.downloadChoices(info);
        assertEquals("1080p60 MP4", choices.get(0).label);
        assertEquals(128, choices.get(0).audio.getAverageBitrate());
    }

    @Test
    public void doesNotOfferManifestsLiveOrInsecureUrlsAsDownloadableFiles() {
        StreamInfo info = info(StreamType.LIVE_STREAM);
        info.setVideoStreams(Collections.singletonList(video("18", "360p", false, DeliveryMethod.PROGRESSIVE_HTTP)));
        assertTrue(NewPipeService.downloadChoices(info).isEmpty());
        assertFalse(NewPipeService.isDownloadable(video("18", "360p", false, DeliveryMethod.HLS)));
        AudioStream insecure = new AudioStream.Builder().setId("140").setContent("http://example.com/audio", true)
                .setMediaFormat(MediaFormat.M4A).setAverageBitrate(128).build();
        assertFalse(NewPipeService.isDownloadable(insecure));
    }

    @Test
    public void filenameCannotEscapeDownloadsDirectoryAndKeepsUniqueSuffix() {
        String name = NewPipeDownloadWorker.filename("../a\\b:c?\n", "abcdefghijk", "12345678", "mp4");
        assertFalse(name.contains("/"));
        assertFalse(name.contains("\\"));
        assertFalse(name.contains(":"));
        assertFalse(name.contains("\n"));
        assertTrue(name.endsWith("-abcdefghijk-12345678.mp4"));
        assertTrue(NewPipeDownloadWorker.filename(null, "abcdefghijk", "12345678", "m4a").startsWith("YouTube-"));
    }

    @Test
    public void rejectsNonVideoIdentifiersBeforeNetworkAccess() throws Exception {
        try {
            NewPipeService.extract("https://example.com/");
            org.junit.Assert.fail("Expected invalid ID rejection");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static StreamInfo info(StreamType type) {
        StreamInfo info = new StreamInfo(0, "https://www.youtube.com/watch?v=abcdefghijk", "", type, "abcdefghijk", "Test", 0);
        info.setVideoStreams(Collections.emptyList());
        info.setVideoOnlyStreams(Collections.emptyList());
        info.setAudioStreams(Collections.emptyList());
        return info;
    }

    private static VideoStream video(String id, String resolution, boolean videoOnly, DeliveryMethod delivery) {
        return new VideoStream.Builder().setId(id).setContent("https://example.com/video/" + id, true)
                .setMediaFormat(MediaFormat.MPEG_4).setIsVideoOnly(videoOnly).setResolution(resolution)
                .setDeliveryMethod(delivery).build();
    }

    private static AudioStream audio(String id, int bitrate) {
        return new AudioStream.Builder().setId(id).setContent("https://example.com/audio/" + id, true)
                .setMediaFormat(MediaFormat.M4A).setAverageBitrate(bitrate).build();
    }
}
