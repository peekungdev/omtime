package dev.olytimesync;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stays up from boot. Hears every Wi-Fi connection and hands it to SyncEngine, and while the screen is on, looks for
 * the camera's Wi-Fi now and then (CameraWifi.probe) so the phone can switch over to it.
 */
public class WatchService extends Service {
    static volatile boolean running;

    private ExecutorService worker;
    private ConnectivityManager cm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            worker.execute(() -> SyncEngine.run(WatchService.this, network, 0));
        }

        @Override
        public void onLost(Network network) {
            if (SyncEngine.cameraNetwork == network.getNetworkHandle()) SyncEngine.cameraNetwork = -1;
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            CameraWifi.probe(WatchService.this);
            handler.postDelayed(this, CameraWifi.PROBE_EVERY_MS);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(tick);
            // Unlocking is when the camera is most likely to have just been switched on; give Wi-Fi a moment to wake.
            if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) handler.postDelayed(tick, 2000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Notes.channels(this);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notes.WATCH_ID, Notes.watching(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(Notes.WATCH_ID, Notes.watching(this));
        }
        worker = Executors.newSingleThreadExecutor();
        cm = getSystemService(ConnectivityManager.class);
        // Also reports a Wi-Fi that is already connected, so a camera joined before boot finished is not missed.
        cm.registerNetworkCallback(Watcher.wifi(), callback);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen, f);
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isInteractive()) handler.postDelayed(tick, 5000);
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.auto(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(tick);
        try {
            unregisterReceiver(screen);
        } catch (RuntimeException e) {
            // not registered
        }
        try {
            cm.unregisterNetworkCallback(callback);
        } catch (RuntimeException e) {
            // already gone
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
