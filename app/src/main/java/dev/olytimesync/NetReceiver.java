package dev.olytimesync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;

/**
 * Fallback path: the system delivers this when a Wi-Fi network connects, even if the phone killed WatchService.
 * The sync is a handful of small requests, so it runs inside the broadcast's own time allowance.
 */
public class NetReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.auto(context)) return;
        if (WatchService.running) return;   // the service hears the same connection
        Network network = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK);
        if (network == null) return;
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                SyncEngine.run(app, network, 0);
            } finally {
                pending.finish();
            }
        }).start();
    }
}
