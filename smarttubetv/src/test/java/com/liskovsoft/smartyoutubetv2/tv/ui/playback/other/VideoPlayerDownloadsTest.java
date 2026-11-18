package com.liskovsoft.smartyoutubetv2.tv.ui.playback.other;

import android.app.Application;
import android.content.Context;

import androidx.leanback.media.PlayerAdapter;
import androidx.leanback.widget.Action;
import androidx.leanback.widget.ArrayObjectAdapter;

import com.liskovsoft.smartyoutubetv2.common.newpipe.NewPipeService;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class VideoPlayerDownloadsTest {
    @After
    public void resetChildMode() {
        GeneralData.instance(RuntimeEnvironment.getApplication()).setChildModeEnabled(false);
    }

    @Test
    public void downloadSectionIsVisibleWithoutEnablingNewPipePlayback() {
        Context context = RuntimeEnvironment.getApplication();
        NewPipeService.setEnabled(context, false);
        GeneralData.instance(context).setChildModeEnabled(false);

        Action action = downloadAction(context);

        assertNotNull(action);
        assertNotNull(action.getIcon());
        assertTrue(action.getLabel1().length() > 0);
        assertFalse(NewPipeService.isEnabled(context));
    }

    @Test
    public void hidesDownloadSectionInChildMode() {
        Context context = RuntimeEnvironment.getApplication();
        GeneralData.instance(context).setChildModeEnabled(true);
        assertNull(downloadAction(context));
    }

    @Test
    @Config(sdk = 23)
    public void hidesUnsupportedDownloadsOnOlderAndroid() {
        Context context = RuntimeEnvironment.getApplication();
        GeneralData.instance(context).setChildModeEnabled(false);
        assertNull(downloadAction(context));
    }

    private static Action downloadAction(Context context) {
        ArrayObjectAdapter actions = new ArrayObjectAdapter();
        new TestGlue(context).createActions(actions);
        for (int i = 0; i < actions.size(); i++) {
            Action action = (Action) actions.get(i);
            if (action.getId() == R.id.action_download) {
                return action;
            }
        }
        return null;
    }

    private static class TestGlue extends VideoPlayerGlue {
        TestGlue(Context context) {
            super(context, new PlayerAdapter() {
                @Override
                public void play() {}

                @Override
                public void pause() {}
            }, null);
        }

        void createActions(ArrayObjectAdapter actions) {
            onCreatePrimaryActions(actions);
        }
    }
}
