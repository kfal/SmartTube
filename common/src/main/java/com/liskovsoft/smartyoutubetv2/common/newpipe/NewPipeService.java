package com.liskovsoft.smartyoutubetv2.common.newpipe;

import android.content.Context;
import android.os.Build;

import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public final class NewPipeService {
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0";
    private static final String ENABLED_KEY = "newpipe_anonymous_playback";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();
    private static boolean sInitialized;

    private NewPipeService() {}

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 24;
    }

    public static boolean isEnabled(Context context) {
        return isSupported() && "true".equals(AppPrefs.instance(context).getProfileData(ENABLED_KEY));
    }

    public static void setEnabled(Context context, boolean enabled) {
        AppPrefs.instance(context).setProfileData(ENABLED_KEY, Boolean.toString(enabled));
    }

    public static OkHttpClient getClient() {
        return CLIENT;
    }

    private static synchronized void initialize() {
        if (!sInitialized) {
            NewPipe.init(new AnonymousDownloader());
            sInitialized = true;
        }
    }

    public static StreamInfo extract(String videoId) throws IOException, ExtractionException {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new IllegalArgumentException("Invalid YouTube video ID");
        }
        initialize();
        return StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + videoId);
    }

    public static List<VideoStream> videoStreams(StreamInfo info) {
        List<VideoStream> streams = new ArrayList<>();
        for (VideoStream stream : info.getVideoStreams()) {
            if (isDownloadable(stream) && stream.getFormat() == MediaFormat.MPEG_4) {
                streams.add(stream);
            }
        }
        for (VideoStream stream : info.getVideoOnlyStreams()) {
            if (isDownloadable(stream) && stream.getFormat() == MediaFormat.MPEG_4 &&
                    (stream.getCodec() == null || stream.getCodec().startsWith("avc"))) {
                streams.add(stream);
            }
        }
        streams.sort(Comparator.comparingInt(NewPipeService::resolution).reversed());
        return streams;
    }

    public static int resolution(VideoStream stream) {
        String value = stream.getResolution();
        int end = value == null ? -1 : value.indexOf('p');
        return end > 0 && value.substring(0, end).matches("\\d+") ? Integer.parseInt(value.substring(0, end)) : 0;
    }

    public static List<AudioStream> audioStreams(StreamInfo info) {
        List<AudioStream> streams = new ArrayList<>();
        for (AudioStream stream : info.getAudioStreams()) {
            if (isDownloadable(stream) && stream.getFormat() == MediaFormat.M4A) {
                streams.add(stream);
            }
        }
        streams.sort(Comparator.comparingInt(AudioStream::getAverageBitrate).reversed());
        return streams;
    }

    public static boolean isDownloadable(Stream stream) {
        return stream.isUrl() && stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP &&
                stream.getContent() != null && stream.getContent().startsWith("https://");
    }

    public static List<DownloadChoice> downloadChoices(StreamInfo info) {
        List<DownloadChoice> choices = new ArrayList<>();
        if (info.getStreamType() != StreamType.VIDEO_STREAM && info.getStreamType() != StreamType.AUDIO_STREAM) {
            return choices;
        }
        List<AudioStream> audio = audioStreams(info);
        for (VideoStream video : videoStreams(info)) {
            if (!video.isVideoOnly() || !audio.isEmpty()) {
                choices.add(new DownloadChoice(video, video.isVideoOnly() ? audio.get(0) : null,
                        video.getResolution() + " MP4", "mp4", "video/mp4"));
            }
        }
        for (AudioStream stream : audio) {
            String language = stream.getAudioLocale() == null ? "" : " " + stream.getAudioLocale().toLanguageTag();
            choices.add(new DownloadChoice(stream, null,
                    stream.getAverageBitrate() + " kbps M4A" + language, "m4a", "audio/mp4"));
        }
        return choices;
    }

    public static final class DownloadChoice {
        public final Stream stream;
        public final AudioStream audio;
        public final String label;
        public final String extension;
        public final String mimeType;

        private DownloadChoice(Stream stream, AudioStream audio, String label, String extension, String mimeType) {
            this.stream = stream;
            this.audio = audio;
            this.label = label;
            this.extension = extension;
            this.mimeType = mimeType;
        }

        public String key() {
            String track = stream instanceof AudioStream ? ((AudioStream) stream).getAudioTrackId() : "";
            return stream.getId() + ":" + stream.getFormatId() + ":" + track;
        }
    }

    static final class AnonymousDownloader extends Downloader {
        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            byte[] data = request.dataToSend();
            RequestBody body = data == null ? null : RequestBody.create(null, data);
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                    .url(request.url()).header("User-Agent", USER_AGENT);
            for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
                // This transport never forwards account credentials, including from SmartTube.
                if ("Cookie".equalsIgnoreCase(header.getKey()) || "Authorization".equalsIgnoreCase(header.getKey())) {
                    continue;
                }
                builder.removeHeader(header.getKey());
                for (String value : header.getValue()) {
                    builder.addHeader(header.getKey(), value);
                }
            }
            builder.method(request.httpMethod(), body);
            try (okhttp3.Response response = CLIENT.newCall(builder.build()).execute()) {
                if (response.code() == 429) {
                    throw new ReCaptchaException("YouTube rate limit; try again later", request.url());
                }
                ResponseBody responseBody = response.body();
                return new Response(response.code(), response.message(), response.headers().toMultimap(),
                        responseBody == null ? "" : responseBody.string(), response.request().url().toString());
            }
        }
    }
}
