package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.chunk.ChunkHolder;
import com.google.android.exoplayer2.source.sabr.DefaultSabrChunkSource;
import com.google.android.exoplayer2.source.sabr.manifest.AdaptationSet;
import com.google.android.exoplayer2.source.sabr.manifest.Period;
import com.google.android.exoplayer2.source.sabr.manifest.Representation;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifest;
import com.google.android.exoplayer2.source.sabr.manifest.SegmentBase.SingleSegmentBase;
import com.google.android.exoplayer2.source.sabr.parser.SabrProcessor;
import com.google.android.exoplayer2.source.sabr.parser.SabrStream;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrPoTokenError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrStreamError;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamProtectionStatus;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamProtectionStatus.Status;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.upstream.ByteArrayDataSource;
import com.google.android.exoplayer2.upstream.LoaderErrorThrower;
import com.google.android.exoplayer2.util.MimeTypes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.Collections;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SabrPlaybackRecoveryTest {
    @Test
    public void pendingAttestationGetsTimeToCompleteInsteadOfFailingImmediately() {
        SabrProcessor processor = processor();
        processor.processStreamProtectionStatus(status(Status.ATTESTATION_PENDING));
        assertNull(processor.getFatalError());
    }

    @Test
    public void stalledAttestationReachesThePlayerErrorHandler() throws Exception {
        String url = "https://example.com/videoplayback";
        Format format = Format.createVideoContainerFormat("137", null, MimeTypes.VIDEO_MP4,
                MimeTypes.VIDEO_H264, null, null, 1_000_000, 1920, 1080, 30, null, 0, 0);
        Representation representation = Representation.newInstance(format, url, new SingleSegmentBase());
        Period period = new Period("test", 0, Collections.singletonList(
                new AdaptationSet(0, C.TRACK_TYPE_VIDEO, Collections.singletonList(representation))));
        SabrManifest manifest = new SabrManifest(C.TIME_UNSET, 60_000, 1_500, false,
                C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, Collections.singletonList(period),
                url, "", null, "test", ClientInfo.getDefaultInstance(), null);
        DefaultSabrChunkSource source = new DefaultSabrChunkSource(new LoaderErrorThrower.Dummy(),
                manifest, 0, new int[] {0}, new FixedTrackSelection(new TrackGroup(format), 0), C.TRACK_TYPE_VIDEO,
                new ByteArrayDataSource(new byte[] {1}), 0, 1, false, Collections.emptyList(), null);
        source.getNextChunk(0, 0, Collections.emptyList(), new ChunkHolder());
        SabrStream stream = ReflectionHelpers.getField(source, "sabrStream");
        SabrProcessor processor = ReflectionHelpers.getField(stream, "processor");
        processor.processStreamProtectionStatus(status(Status.ATTESTATION_REQUIRED));
        SabrStreamError error = ageAttestationError(processor);

        assertTrue(error instanceof SabrPoTokenError);
        try {
            source.maybeThrowError();
            fail("A stalled PoToken failure must reach client recovery instead of buffering forever");
        } catch (SabrPoTokenError expected) {
            assertSame(error, expected);
        }
    }

    @Test
    public void successfulAttestationClearsThePendingError() {
        SabrProcessor processor = processor();
        processor.processStreamProtectionStatus(status(Status.ATTESTATION_REQUIRED));
        ageAttestationError(processor);
        processor.processStreamProtectionStatus(status(Status.OK));
        assertNull(processor.getFatalError());
    }

    private static SabrStreamError ageAttestationError(SabrProcessor processor) {
        SabrStreamError error = ReflectionHelpers.getField(processor, "fatalError");
        ReflectionHelpers.setField(error, "createdAtMs", System.currentTimeMillis() - 2_000);
        return processor.getFatalError();
    }

    private static StreamProtectionStatus status(Status status) {
        return StreamProtectionStatus.newBuilder().setStatus(status).build();
    }

    private static SabrProcessor processor() {
        return new SabrProcessor("", ClientInfo.getDefaultInstance(), -1, -1, 0, null, false, "test", 60_000);
    }
}
