package org.lineageos.recorder.screen;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;

import org.lineageos.recorder.screen.ScreencastService;
import org.lineageos.recorder.utils.PermissionUtils;
import org.lineageos.recorder.utils.Utils;
import org.lineageos.recorder.R;

public class ScreencastOverlayHelper extends Activity {

    private static final int REQUEST_AUDIO_VIDEO = 442;

    @Override
    public void onCreate(Bundle savedInstance) {
    	super.onCreate(savedInstance);
    	toggleScreenRecorder();
    }

    private void toggleScreenRecorder() {
        if (checkScreenRecPermissions()) {
            return;
        }

        if (Utils.isScreenRecording(this)) {
            // Stop
            Utils.setStatus(this, Utils.UiStatus.NOTHING);
            startService(new Intent(ScreencastService.ACTION_STOP_SCREENCAST)
                    .setClass(this, ScreencastService.class));
            this.finish();
        } else {
            // Start
            MediaProjectionManager mediaProjectionManager = getSystemService(
                    MediaProjectionManager.class);
            if (mediaProjectionManager == null) {
                return;
            }

            Intent permissionIntent = mediaProjectionManager.createScreenCaptureIntent();
            startActivityForResult(permissionIntent, REQUEST_AUDIO_VIDEO);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        int audioSource = Utils.getAudioRecordingSource(this);
        if (requestCode == REQUEST_AUDIO_VIDEO && resultCode == Activity.RESULT_OK) {
            Intent intent = new Intent(this, OverlayService.class);
            intent.putExtra(OverlayService.EXTRA_AUDIO_SOURCE, audioSource);
            intent.putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode);
            intent.putExtra(OverlayService.EXTRA_RESULT_DATA, data);
            startService(intent);
            this.finish();
        }
    }

    private boolean checkScreenRecPermissions() {
        if (!PermissionUtils.hasDrawOverOtherAppsPermission(this)) {
            Intent overlayIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_permissions_title)
                    .setMessage(getString(R.string.dialog_permissions_overlay))
                    .setPositiveButton(getString(R.string.screen_audio_warning_button_ask),
                            (dialog, which) -> startActivityForResult(overlayIntent, REQUEST_AUDIO_VIDEO))
                    .show();
            return true;
        }
        return false;
    }

}
