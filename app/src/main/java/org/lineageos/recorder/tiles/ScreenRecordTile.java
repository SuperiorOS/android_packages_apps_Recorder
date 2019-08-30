package org.lineageos.recorder.tiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.lineageos.recorder.R;
import org.lineageos.recorder.RecorderActivity;
import org.lineageos.recorder.screen.OverlayService;
import org.lineageos.recorder.screen.ScreencastOverlayHelper;
import org.lineageos.recorder.screen.ScreencastService;
import org.lineageos.recorder.utils.GlobalSettings;
import org.lineageos.recorder.utils.PermissionUtils;
import org.lineageos.recorder.utils.Utils;

public class ScreenRecordTile extends TileService {

    private final BroadcastReceiver mRecordingStateChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Utils.ACTION_RECORDING_STATE_CHANGED.equals(intent.getAction())) {
                updateTile();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onClick() {
        if (Utils.isScreenRecording(this)) {
            Utils.collapseStatusBar(this);
            Utils.setStatus(this, Utils.UiStatus.NOTHING);
            startService(new Intent(ScreencastService.ACTION_STOP_SCREENCAST)
                    .setClass(this, ScreencastService.class));
        } else if (hasPerms()) {
            Utils.collapseStatusBar(this);
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Utils.ACTION_HIDE_ACTIVITY));
            Utils.stopOverlayService(this);
            final Intent permIntent = new Intent(this, ScreencastOverlayHelper.class);
            permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(permIntent);
        } else {
            Intent intent = new Intent(this, RecorderActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(RecorderActivity.EXTRA_UI_TYPE, Utils.UiStatus.SCREEN.toString());
            startActivityAndCollapse(intent);
        }
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
        LocalBroadcastManager.getInstance(this).registerReceiver(mRecordingStateChanged,
                new IntentFilter(Utils.ACTION_RECORDING_STATE_CHANGED));
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mRecordingStateChanged);
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    private void updateTile() {
        Tile qsTile = getQsTile();
        if (GlobalSettings.sRecordingStatus.equals(Utils.PREF_RECORDING_SOUND)) {
            qsTile.setState(Tile.STATE_UNAVAILABLE);
        } else {
            qsTile.setState(GlobalSettings.sRecordingStatus.equals(Utils.PREF_RECORDING_SCREEN)
                    ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        }
        qsTile.setLabel(getString(Utils.isScreenRecording(this) ?
                R.string.screen_recording_message : R.string.screen_notification_title));
        qsTile.updateTile();
    }


    private boolean hasPerms() {
        if (!PermissionUtils.hasDrawOverOtherAppsPermission(this)) {
            return false;
        }
        return true;
    }
}
