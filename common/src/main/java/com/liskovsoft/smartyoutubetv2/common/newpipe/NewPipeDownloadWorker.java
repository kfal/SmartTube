package com.liskovsoft.smartyoutubetv2.common.newpipe;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.newpipe.NewPipeService.DownloadChoice;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class NewPipeDownloadWorker extends Worker {
    public static final String TAG = "newpipe-download";
    public static final String VIDEO_ID = "video_id";
    public static final String CHOICE = "choice";
    public static final String TITLE = "title";
    public static final String ERROR = "error";
    public static final String PATH = "path";
    private static final String CHANNEL = "newpipe_downloads";
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private final int mNotificationId;
    private volatile Call mCall;

    public NewPipeDownloadWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
        mNotificationId = getId().hashCode() & 0x3fffffff;
    }

    @NonNull
    @Override
    public Result doWork() {
        File primary = new File(getApplicationContext().getFilesDir(), getId() + ".download");
        File audio = new File(getApplicationContext().getFilesDir(), getId() + ".audio");
        File merged = new File(getApplicationContext().getFilesDir(), getId() + ".mp4");
        try {
            if (!NewPipeService.isSupported()) {
                throw new IOException("NewPipe requires Android 7 or newer");
            }
            createChannel();
            setForegroundAsync(foregroundInfo()).get();
            DownloadChoice choice = null;
            for (DownloadChoice candidate : NewPipeService.downloadChoices(
                    NewPipeService.extract(getInputData().getString(VIDEO_ID)))) {
                if (candidate.key().equals(getInputData().getString(CHOICE))) {
                    choice = candidate;
                    break;
                }
            }
            if (choice == null) {
                throw new IOException(getApplicationContext().getString(R.string.newpipe_no_streams));
            }
            download(choice.stream.getContent(), primary);
            File result = primary;
            if (choice.audio != null) {
                download(choice.audio.getContent(), audio);
                mux(primary, audio, merged);
                result = merged;
            }
            checkCancelled();
            String filename = filename(getInputData().getString(TITLE),
                    getInputData().getString(VIDEO_ID), getId().toString().substring(0, 8), choice.extension);
            publish(result, filename, choice.mimeType);
            String path = "Download/SmartTube/" + filename;
            notifyFinished(getApplicationContext().getString(R.string.newpipe_download_complete), path);
            return Result.success(new Data.Builder().putString(PATH, path)
                    .putString(TITLE, getInputData().getString(TITLE)).build());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failed(error);
        } catch (IOException | ExtractionException | ExecutionException | IllegalArgumentException |
                 IllegalStateException | SecurityException error) {
            return failed(error);
        } finally {
            deleteTemporary(primary);
            deleteTemporary(audio);
            deleteTemporary(merged);
        }
    }

    private Result failed(Exception error) {
        Log.e(TAG, "Download failed: %s", error);
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (!isStopped()) {
            notifyFinished(getApplicationContext().getString(R.string.newpipe_download_failed), message);
        }
        return Result.failure(new Data.Builder().putString(ERROR,
                message.substring(0, Math.min(message.length(), 1000)))
                .putString(TITLE, getInputData().getString(TITLE)).build());
    }

    @Override
    public void onStopped() {
        Call call = mCall;
        if (call != null) {
            call.cancel();
        }
    }

    private void checkCancelled() throws InterruptedIOException {
        if (isStopped() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Download cancelled");
        }
    }

    void download(String url, File destination) throws IOException {
        try (OutputStream output = new FileOutputStream(destination)) {
            long offset = 0;
            long total;
            do {
                checkCancelled();
                Request request = new Request.Builder().url(url)
                        .header("User-Agent", NewPipeService.USER_AGENT)
                        .header("Range", "bytes=" + offset + "-" + (offset + 10 * 1024 * 1024 - 1))
                        .build();
                mCall = NewPipeService.getClient().newCall(request);
                try (Response response = mCall.execute()) {
                    ResponseBody body = response.body();
                    if (!response.isSuccessful() || body == null) {
                        throw new IOException("Media download HTTP " + response.code());
                    }
                    long expected;
                    if (response.code() == 206) {
                        Matcher range = CONTENT_RANGE.matcher(response.header("Content-Range", ""));
                        if (!range.matches() || Long.parseLong(range.group(1)) != offset) {
                            throw new IOException("Invalid media Content-Range");
                        }
                        total = Long.parseLong(range.group(3));
                        expected = Long.parseLong(range.group(2)) - offset + 1;
                        if (expected <= 0 || total < offset + expected) {
                            throw new IOException("Invalid media length");
                        }
                    } else {
                        if (offset != 0) {
                            throw new IOException("Server did not honor the media range request");
                        }
                        expected = body.contentLength();
                        total = expected;
                    }
                    long copied = copy(body.byteStream(), output);
                    if (copied == 0 || (expected >= 0 && copied != expected)) {
                        throw new IOException("Incomplete media download");
                    }
                    offset += copied;
                    setProgressAsync(new Data.Builder().putLong("bytes", offset).putLong("total", total)
                            .putString(TITLE, getInputData().getString(TITLE)).build());
                } finally {
                    mCall = null;
                }
            } while (total > offset);
        }
    }

    private long copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            checkCancelled();
            output.write(buffer, 0, read);
            count += read;
        }
        return count;
    }

    private void mux(File video, File audio, File destination) throws IOException {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            videoExtractor.setDataSource(video.getAbsolutePath());
            audioExtractor.setDataSource(audio.getAbsolutePath());
            MediaFormat videoFormat = selectTrack(videoExtractor, "video/");
            MediaFormat audioFormat = selectTrack(audioExtractor, "audio/");
            muxer = new MediaMuxer(destination.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack = muxer.addTrack(videoFormat);
            int audioTrack = muxer.addTrack(audioFormat);
            muxer.start();
            copyTrack(videoExtractor, muxer, videoTrack, videoFormat);
            copyTrack(audioExtractor, muxer, audioTrack, audioFormat);
            muxer.stop();
        } finally {
            videoExtractor.release();
            audioExtractor.release();
            if (muxer != null) {
                muxer.release();
            }
        }
    }

    private static MediaFormat selectTrack(MediaExtractor extractor, String type) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(type)) {
                extractor.selectTrack(i);
                return format;
            }
        }
        throw new IOException("Downloaded file has no " + type + " track");
    }

    private void copyTrack(MediaExtractor extractor, MediaMuxer muxer, int track, MediaFormat format) throws IOException {
        int size = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
        if (size > 32 * 1024 * 1024) {
            throw new IOException("Media sample too large");
        }
        ByteBuffer buffer = ByteBuffer.allocate(Math.max(size, 8 * 1024 * 1024));
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean hasSamples = false;
        while (true) {
            checkCancelled();
            buffer.clear();
            int read = extractor.readSampleData(buffer, 0);
            if (read < 0) {
                break;
            }
            if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                throw new IOException("Encrypted downloads are not supported");
            }
            info.set(0, read, extractor.getSampleTime(), extractor.getSampleFlags() & MediaCodec.BUFFER_FLAG_KEY_FRAME);
            muxer.writeSampleData(track, buffer, info);
            hasSamples = true;
            extractor.advance();
        }
        if (!hasSamples) {
            throw new IOException("Downloaded media track is empty");
        }
    }

    private void publish(File source, String filename, String mimeType) throws IOException {
        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SmartTube");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Cannot create download in MediaStore");
            }
            boolean complete = false;
            try {
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                    if (output == null) {
                        throw new IOException("Cannot open download destination");
                    }
                    copy(input, output);
                }
                checkCancelled();
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                if (context.getContentResolver().update(uri, values, null, null) != 1) {
                    throw new IOException("Cannot finalize download");
                }
                complete = true;
            } finally {
                if (!complete) {
                    context.getContentResolver().delete(uri, null, null);
                }
            }
        } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SmartTube");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cannot create Downloads/SmartTube");
            }
            File destination = new File(directory, filename);
            boolean complete = false;
            try {
                try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(destination)) {
                    copy(input, output);
                }
                checkCancelled();
                complete = true;
                MediaScannerConnection.scanFile(context, new String[] {destination.getAbsolutePath()}, new String[] {mimeType}, null);
            } finally {
                if (!complete) {
                    deleteTemporary(destination);
                }
            }
        }
    }

    static String filename(String title, String videoId, String uniqueId, String extension) {
        String safe = title == null ? "YouTube" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        safe = safe.substring(0, Math.min(60, safe.length()));
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) {
            safe = "YouTube";
        }
        return safe + "-" + videoId + "-" + uniqueId + "." + extension;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getApplicationContext().getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, getApplicationContext().getString(R.string.newpipe_downloads),
                            NotificationManager.IMPORTANCE_LOW));
        }
    }

    private ForegroundInfo foregroundInfo() {
        NotificationCompat.Builder notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getInputData().getString(TITLE))
                .setContentText(getApplicationContext().getString(R.string.newpipe_downloading))
                .setOngoing(true).setProgress(0, 0, true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getApplicationContext().getString(android.R.string.cancel),
                        WorkManager.getInstance(getApplicationContext()).createCancelPendingIntent(getId()));
        return Build.VERSION.SDK_INT >= 29 ?
                new ForegroundInfo(mNotificationId, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) :
                new ForegroundInfo(mNotificationId, notification.build());
    }

    private void notifyFinished(String title, String detail) {
        NotificationManager manager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            createChannel();
            manager.notify(mNotificationId + 0x40000000, new NotificationCompat.Builder(getApplicationContext(), CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(title)
                    .setContentText(detail).setStyle(new NotificationCompat.BigTextStyle().bigText(detail))
                    .setAutoCancel(true).build());
        } catch (SecurityException error) {
            Log.e(TAG, "Download notification permission denied: %s", error);
        }
    }

    private static void deleteTemporary(File file) {
        if (file.exists() && !file.delete()) {
            Log.e(TAG, "Cannot remove temporary download: %s", file);
        }
    }
}
