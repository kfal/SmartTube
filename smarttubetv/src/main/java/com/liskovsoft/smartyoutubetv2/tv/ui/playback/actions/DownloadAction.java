package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;

import androidx.core.content.ContextCompat;
import androidx.leanback.widget.Action;

import com.liskovsoft.smartyoutubetv2.tv.R;

public class DownloadAction extends Action {
    public DownloadAction(Context context) {
        super(R.id.action_download);
        setIcon(ContextCompat.getDrawable(context, R.drawable.action_download));
        setLabel1(context.getString(R.string.newpipe_download_section));
    }
}
