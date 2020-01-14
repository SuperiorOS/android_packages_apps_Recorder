/*
 * Copyright (C) 2017 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.recorder.utils;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.provider.Settings;
import android.util.DisplayMetrics;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.lineageos.recorder.screen.OverlayService;
import org.lineageos.recorder.utils.GlobalSettings;
import java.io.Closeable;
import java.io.IOException;

public class Utils {
    public static final String PREFS = "preferences";
    public static final String SCREEN_PREFS = "screen_preferences";
    //public static final String KEY_RECORDING = "recording";
    public static final String ACTION_RECORDING_STATE_CHANGED = "org.lineageos.recorder.RECORDING_STATE_CHANGED";
    public static final String ACTION_HIDE_ACTIVITY = "org.lineageos.recorder.HIDE_ACTIVITY";
    public static final String PREF_RECORDING_NOTHING = "nothing";
    public static final String PREF_RECORDING_SCREEN = "screen";
    public static final String PREF_RECORDING_SOUND = "sound";
    public static final String PREF_AUDIO_RECORDING_SOURCE = "audio_recording_source";
    public static final String PREF_SCREEN_RECORDING_QUALITY = "screen_recording_quality";
    public static final String PREF_SCREEN_RECORDING_TAPS = "screen_recording_showtaps";
    public static final int PREF_AUDIO_RECORDING_SOURCE_DISABLED = 0;
    public static final int PREF_AUDIO_RECORDING_SOURCE_INTERNAL = 1;
    public static final int PREF_AUDIO_RECORDING_SOURCE_MICROPHONE = 2;
    public static final int PREF_AUDIO_RECORDING_SOURCE_DEFAULT = PREF_AUDIO_RECORDING_SOURCE_DISABLED;
    public static final int PREF_VIDEO_RECORDING_BITRATE_LOW = 4000000;
    public static final int PREF_VIDEO_RECORDING_BITRATE_MEDIUM = 5500000;
    public static final int PREF_VIDEO_RECORDING_BITRATE_HIGH = 7500000;
    public static final int PREF_VIDEO_RECORDING_BITRATE_DEFAULT = 1;
    public static final boolean PREF_SCREEN_RECORDING_TAPS_DEFAULT = false;

    private Utils() {
    }

    private static String getStatus() {
        return GlobalSettings.sRecordingStatus;
    }

    public static void setShowTaps(Context context, boolean isEnabled) {
        int value = isEnabled ? 1 : 0;
        Settings.System.putInt(context.getContentResolver(),
                "show_touches", value);
    }

    public static void setStatus(Context context, UiStatus status) {
        if (status.equals(UiStatus.SOUND)) {
            setStatus(context, PREF_RECORDING_SOUND);
        } else if (status.equals(UiStatus.SCREEN)) {
            setStatus(context, PREF_RECORDING_SCREEN);
        } else {
            setStatus(context, PREF_RECORDING_NOTHING);
        }
    }

    public static void setStatus(Context context, String status) {
        GlobalSettings.sRecordingStatus = status;
        LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(ACTION_RECORDING_STATE_CHANGED));
    }

    public static boolean isRecording(Context context) {
        return !PREF_RECORDING_NOTHING.equals(getStatus());
    }

    public static boolean isSoundRecording(Context context) {
        return PREF_RECORDING_SOUND.equals(getStatus());
    }

    public static boolean isScreenRecording(Context context) {
        return PREF_RECORDING_SCREEN.equals(getStatus());
    }

    public static int getAudioRecordingSource(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Utils.PREFS, 0);
        return prefs.getInt(Utils.PREF_AUDIO_RECORDING_SOURCE, Utils.PREF_AUDIO_RECORDING_SOURCE_DEFAULT);
    }

    public static int getVideoRecordingBitrate(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Utils.PREFS, 0);
        return prefs.getInt(Utils.PREF_SCREEN_RECORDING_QUALITY, Utils.PREF_VIDEO_RECORDING_BITRATE_DEFAULT);
    }

    public static boolean getShowTapsConfig(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Utils.PREFS, 0);
        return prefs.getBoolean(Utils.PREF_SCREEN_RECORDING_TAPS, Utils.PREF_SCREEN_RECORDING_TAPS_DEFAULT);
    }

    @SuppressWarnings("SameParameterValue")
    public static int convertDp2Px(Context context, int dp) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.round(dp * metrics.density + 0.5f);
    }

    public static int darkenedColor(int color) {
        int alpha = Color.alpha(color);
        int red = getDarkenedColorValue(Color.red(color));
        int green = getDarkenedColorValue(Color.green(color));
        int blue = getDarkenedColorValue(Color.blue(color));
        return Color.argb(alpha, red, green, blue);
    }

    private static int getDarkenedColorValue(int value) {
        float dark = 0.8f; // -20% lightness
        return Math.min(Math.round(value * dark), 255);
    }

    public static void stopOverlayService(Context context) {
        // Stop overlay service if running
        if (OverlayService.isRunning) {
            context.stopService(new Intent(context, OverlayService.class));
        }
    }

    /**
     * Unconditionally close a <code>Closeable</code>.
     * <p>
     * Equivalent to {@link Closeable#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     * <p>
     * Example code:
     * <pre>
     *   Closeable closeable = null;
     *   try {
     *       closeable = new FileReader("foo.txt");
     *       // process closeable
     *       closeable.close();
     *   } catch (Exception e) {
     *       // error handling
     *   } finally {
     *       IOUtils.closeQuietly(closeable);
     *   }
     * </pre>
     *
     * @param closeable the object to close, may be null or already closed
     * @since 2.0
     */
    public static void closeQuietly(Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException ioe) {
            // ignore
        }
    }

    public static void collapseStatusBar(Context context) {
        context.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
    }

    public enum UiStatus {
        NOTHING,
        SOUND,
        SCREEN
    }

}
