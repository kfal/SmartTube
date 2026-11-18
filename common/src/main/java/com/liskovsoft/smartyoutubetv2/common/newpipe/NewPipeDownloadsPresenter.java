package com.liskovsoft.smartyoutubetv2.common.newpipe;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.storage.StorageVolume;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.newpipe.NewPipeService.DownloadChoice;

import java.io.IOException;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class NewPipeDownloadsPresenter extends BasePresenter<Void> {
    private static final int REQUEST_DOWNLOAD_PERMISSION = 17346;
    private static final int REQUEST_USB_ACCESS = 17347;
    private Disposable mAction;
    private Video mSectionVideo;

    private NewPipeDownloadsPresenter(Context context) {
        super(context);
    }

    public static NewPipeDownloadsPresenter instance(Context context) {
        return new NewPipeDownloadsPresenter(context);
    }

    public void showSection(Video video) {
        mSectionVideo = video;
        showDownloads();
    }

    public void show(Video video) {
        if (!NewPipeService.isSupported() || video == null || !video.hasVideo()) {
            MessageHelpers.showMessage(getContext(), R.string.newpipe_no_streams);
            return;
        }
        showLoading();
        mAction = Observable.fromCallable(() -> NewPipeService.downloadChoices(NewPipeService.extract(video.videoId)))
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(choices -> showChoices(video, choices), this::showError);
    }

    private void showLoading() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.wait_data_loading),
                option -> dialog.closeDialog()));
        dialog.showDialog(getContext().getString(R.string.newpipe_downloads), () -> {
            if (mAction != null) {
                mAction.dispose();
            }
        });
    }

    private void showChoices(Video video, List<DownloadChoice> choices) {
        if (getContext() == null) {
            return;
        }
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        if (choices.isEmpty()) {
            dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.newpipe_no_streams),
                    option -> dialog.closeDialog()));
        }
        for (DownloadChoice choice : choices) {
            dialog.appendSingleButton(UiOptionItem.from(choice.label,
                    option -> requestPermissionAndEnqueue(video, choice)));
        }
        dialog.showDialog(getContext().getString(R.string.newpipe_download));
    }

    private void requestPermissionAndEnqueue(Video video, DownloadChoice choice) {
        Context context = getContext();
        try {
            StorageVolume volume = NewPipeDownloadStorage.preferredVolume(context);
            Uri tree = null;
            if (volume != null) {
                NewPipeDownloadStorage.requireMounted(context, volume.getUuid());
                tree = NewPipeDownloadStorage.savedTree(context, volume.getUuid());
                if (tree == null) {
                    requestUsbAccess(video, choice, volume);
                    return;
                }
            }
            requestDownloadPermission(video, choice, NewPipeDownloadStorage.destination(volume, tree));
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException error) {
            showError(error);
        }
    }

    private void requestUsbAccess(Video video, DownloadChoice choice, StorageVolume volume) {
        if (!(getContext() instanceof MotherActivity)) {
            showError(new IOException(getContext().getString(R.string.newpipe_usb_access_denied)));
            return;
        }
        MotherActivity activity = (MotherActivity) getContext();
        Intent intent = NewPipeDownloadStorage.accessIntent(volume);
        activity.addOnResult((requestCode, resultCode, data) -> {
            if (requestCode != REQUEST_USB_ACCESS) {
                return;
            }
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                MessageHelpers.showLongMessage(activity, R.string.newpipe_usb_access_denied);
                return;
            }
            try {
                Uri tree = data.getData();
                NewPipeDownloadStorage.saveTree(activity, volume.getUuid(), tree, data.getFlags());
                requestDownloadPermission(video, choice, NewPipeDownloadStorage.destination(volume, tree));
            } catch (IOException | IllegalArgumentException | SecurityException error) {
                showError(error);
            }
        });
        MessageHelpers.showLongMessage(activity, R.string.newpipe_usb_select_folder);
        try {
            activity.startActivityForResult(intent, REQUEST_USB_ACCESS);
        } catch (ActivityNotFoundException error) {
            showError(new IOException(activity.getString(R.string.newpipe_usb_picker_missing), error));
        } catch (SecurityException error) {
            showError(error);
        }
    }

    private void requestDownloadPermission(Video video, DownloadChoice choice, Data destination) {
        Context context = getContext();
        String permission = Build.VERSION.SDK_INT <= 28 && destination.getString(NewPipeDownloadStorage.TREE) == null ?
                Manifest.permission.WRITE_EXTERNAL_STORAGE :
                Build.VERSION.SDK_INT >= 33 ? Manifest.permission.POST_NOTIFICATIONS : null;
        if (permission != null && ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            if (!(context instanceof MotherActivity)) {
                showError(new IllegalStateException(context.getString(R.string.newpipe_storage_denied)));
                return;
            }
            MotherActivity activity = (MotherActivity) context;
            activity.addOnPermissions((requestCode, permissions, results) -> {
                if (requestCode == REQUEST_DOWNLOAD_PERMISSION) {
                    if (Build.VERSION.SDK_INT <= 28 &&
                            (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
                        MessageHelpers.showLongMessage(activity, R.string.newpipe_storage_denied);
                    } else {
                        enqueue(video, choice, destination);
                    }
                }
            });
            ActivityCompat.requestPermissions(activity, new String[] {permission}, REQUEST_DOWNLOAD_PERMISSION);
        } else {
            enqueue(video, choice, destination);
        }
    }

    private void enqueue(Video video, DownloadChoice choice, Data destination) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(NewPipeDownloadWorker.class)
                .setInputData(new Data.Builder().putAll(destination).putString(NewPipeDownloadWorker.VIDEO_ID, video.videoId)
                        .putString(NewPipeDownloadWorker.CHOICE, choice.key())
                        .putString(NewPipeDownloadWorker.TITLE, video.getTitle()).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(NewPipeDownloadWorker.TAG)
                .addTag("title:" + video.getTitle()).build();
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        String key = "newpipe-" + video.videoId + "-" + choice.key();
        mAction = Observable.fromCallable(() -> {
            manager.enqueueUniqueWork(key, ExistingWorkPolicy.KEEP, work).getResult().get();
            return manager.getWorkInfoById(work.getId()).get() != null;
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(queued -> {
                    if (getContext() != null) {
                        MessageHelpers.showLongMessage(getContext(), queued ?
                                getContext().getString(R.string.newpipe_download_queued, NewPipeDownloadStorage.location(destination)) :
                                getContext().getString(R.string.newpipe_download_already_queued));
                    }
                }, this::showError);
    }

    public void showDownloads() {
        Context context = getContext();
        showLoading();
        mAction = Observable.fromCallable(() -> WorkManager.getInstance(context.getApplicationContext())
                        .getWorkInfosByTag(NewPipeDownloadWorker.TAG).get())
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::showWork, this::showError);
    }

    private void showWork(List<WorkInfo> jobs) {
        if (getContext() == null) {
            return;
        }
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        if (mSectionVideo != null && mSectionVideo.hasVideo()) {
            dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.newpipe_download_current),
                    getContext().getString(R.string.newpipe_download_default),
                    option -> show(mSectionVideo)));
        }
        if (jobs.isEmpty()) {
            dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.newpipe_downloads_empty),
                    option -> dialog.closeDialog()));
        }
        for (WorkInfo job : jobs) {
            String title = job.getId().toString().substring(0, 8);
            for (String tag : job.getTags()) {
                if (tag.startsWith("title:")) {
                    title = tag.substring(6);
                    break;
                }
            }
            String detail = job.getOutputData().getString(NewPipeDownloadWorker.ERROR);
            if (job.getState() == WorkInfo.State.SUCCEEDED) {
                detail = job.getOutputData().getString(NewPipeDownloadWorker.PATH);
            }
            String description = detail;
            dialog.appendSingleButton(UiOptionItem.from(title + " - " + stateLabel(job.getState()), description, option -> {
                if (!job.getState().isFinished()) {
                    cancel(job);
                } else if (description != null) {
                    MessageHelpers.showLongMessage(getContext(), description);
                }
            }));
        }
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.newpipe_refresh),
                option -> showDownloads()));
        dialog.showDialog(getContext().getString(mSectionVideo == null ?
                R.string.newpipe_downloads : R.string.newpipe_download_section));
    }

    private void cancel(WorkInfo job) {
        WorkManager manager = WorkManager.getInstance(getContext().getApplicationContext());
        mAction = Observable.fromCallable(() -> {
            manager.cancelWorkById(job.getId()).getResult().get();
            return true;
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(cancelled -> showDownloads(), this::showError);
    }

    private String stateLabel(WorkInfo.State state) {
        int resource;
        switch (state) {
            case RUNNING: resource = R.string.newpipe_downloading; break;
            case SUCCEEDED: resource = R.string.newpipe_download_complete; break;
            case FAILED: resource = R.string.newpipe_download_failed; break;
            case CANCELLED: resource = R.string.newpipe_download_cancelled; break;
            default: resource = R.string.newpipe_download_waiting; break;
        }
        return getContext().getString(resource);
    }

    private void showError(Throwable error) {
        Log.e(NewPipeDownloadWorker.TAG, "NewPipe operation failed: %s", error);
        if (getContext() != null) {
            AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
            dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.newpipe_error, error.getMessage()),
                    option -> dialog.closeDialog()));
            dialog.showDialog(getContext().getString(R.string.newpipe_downloads));
        }
    }
}
