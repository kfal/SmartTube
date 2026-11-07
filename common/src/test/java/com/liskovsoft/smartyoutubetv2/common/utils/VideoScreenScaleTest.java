package com.liskovsoft.smartyoutubetv2.common.utils;

import android.content.Context;
import android.view.View;

import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoScreenScaleTest {
    private static final int[] SCALES = {100, 90, 80, 70, 60, 50};
    private Context mContext;
    private PlayerData mPlayerData;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        mPlayerData = PlayerData.instance(mContext);
        mPlayerData.setResizeMode(PlayerData.RESIZE_MODE_DEFAULT);
        mPlayerData.setZoomPercents(-1);
    }

    @Test
    public void offersRequestedPresetsAndSelectsDefaultSize() {
        OptionCategory category = AppDialogUtil.createVideoScreenScaleCategory(mContext, () -> {});

        assertEquals(mContext.getString(R.string.video_screen_scale), category.title);
        assertEquals(OptionCategory.TYPE_RADIO_LIST, category.type);
        assertEquals(SCALES.length, category.options.size());
        for (int i = 0; i < SCALES.length; i++) {
            assertEquals(SCALES[i] + "%", category.options.get(i).getTitle());
            assertEquals(i == 0, category.options.get(i).isSelected());
        }
    }

    @Test
    public void eachPresetPersistsAndInvokesCallbackWithFittedResizeMode() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        for (int i = 0; i < SCALES.length; i++) {
            mPlayerData.setResizeMode(PlayerData.RESIZE_MODE_STRETCH);
            OptionCategory category = AppDialogUtil.createVideoScreenScaleCategory(mContext, () -> {
                assertEquals(PlayerData.RESIZE_MODE_DEFAULT, mPlayerData.getResizeMode());
                callbacks.incrementAndGet();
            });
            category.options.get(i).onSelect(true);

            assertEquals(SCALES[i], mPlayerData.getZoomPercents());
            assertEquals(i + 1, callbacks.get());
            ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS);

            Field instance = PlayerData.class.getDeclaredField("sInstance");
            instance.setAccessible(true);
            instance.set(null, null);
            mPlayerData = PlayerData.instance(mContext);
            assertEquals(SCALES[i], mPlayerData.getZoomPercents());
            assertEquals(PlayerData.RESIZE_MODE_DEFAULT, mPlayerData.getResizeMode());
            assertTrue(AppDialogUtil.createVideoScreenScaleCategory(mContext, () -> {}).options.get(i).isSelected());
        }
    }

    @Test
    public void doesNotMislabelOtherZoomModesAsFullSize() {
        mPlayerData.setResizeMode(PlayerData.RESIZE_MODE_STRETCH);
        for (OptionItem option : AppDialogUtil.createVideoScreenScaleCategory(mContext, () -> {}).options) {
            assertFalse(option.isSelected());
        }
        mPlayerData.setZoomPercents(85);
        for (OptionItem option : AppDialogUtil.createVideoScreenScaleCategory(mContext, () -> {}).options) {
            assertFalse(option.isSelected());
        }
    }

    @Test
    public void existingZoomListKeepsDefaultAndPercentSelectionsDistinct() {
        OptionCategory category = AppDialogUtil.createVideoZoomCategory(mContext);
        assertTrue(category.options.get(0).isSelected());
        for (OptionItem option : category.options) {
            if ("100%".contentEquals(option.getTitle())) {
                assertFalse(option.isSelected());
            }
        }
    }

    @Test
    public void measuresExactPercentagesForWidescreenAndFourByThreeVideo() {
        for (int scale : SCALES) {
            assertMeasuredSize(16f / 9, scale, 1920 * scale / 100, 1080 * scale / 100);
            assertMeasuredSize(4f / 3, scale, 1440 * scale / 100, 1080 * scale / 100);
        }
    }

    private void assertMeasuredSize(float aspectRatio, int scale, int expectedWidth, int expectedHeight) {
        AspectRatioFrameLayout layout = new AspectRatioFrameLayout(mContext);
        layout.setAspectRatio(aspectRatio);
        layout.setZoom(scale);
        layout.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));

        assertEquals(expectedWidth, layout.getMeasuredWidth());
        assertEquals(expectedHeight, layout.getMeasuredHeight());
    }
}
