package dev.olytimesync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Phone started (or the app was updated): start watching for the camera again. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Prefs.auto(context)) Watcher.arm(context);
    }
}
