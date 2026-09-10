package com.antigravity.tvvolume;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "TvVolumeBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "Received boot intent: " + action);

        // 1. Start the overlay foreground service
        Intent serviceIntent = new Intent(context, TvVolumeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        // 2. Automatically launch the native volume_bridge daemon via local ADB on boot
        AdbStarter.ensureBridgeRunningAsync();
    }
}
