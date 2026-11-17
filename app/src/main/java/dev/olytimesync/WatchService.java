package dev.olytimesync;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

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
            // A fresh scan answers through the scan receiver below. Without one (no scan access, or Android
            // throttled the request), fall back to simply trying to join the camera, at most every few minutes.
            long now = SystemClock.elapsedRealtime();
            if (!CameraWifi.requestScan(WatchService.this)
                    && (lastFallback == 0 || now - lastFallback >= CameraWifi.PROBE_EVERY_MS)) {
                lastFallback = now;
                CameraWifi.probe(WatchService.this, false);
            }
            handler.postDelayed(this, CameraWifi.SCAN_EVERY_MS);
        }
    };
    private long lastFallback;
    private long lastScanLog = -10 * 60 * 1000L;

    /** Every Wi-Fi scan the phone makes: a camera whose Wi-Fi just came on is synced straight away. */
    private final BroadcastReceiver scans = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastScanLog > 10 * 60 * 1000L) {   // enough to show scans arrive, without filling the log
                lastScanLog = now;
                Log.i(CameraWifi.TAG, "Wi-Fi scan results arrive");
            }
            CameraWifi.probe(WatchService.this, true);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(tick);
            // Unlocking is when the camera is most likely to have just been switched on: check straight away rather
            // than at the next fallback slot. Give Wi-Fi a moment to wake first.
            if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                lastFallback = 0;
                handler.postDelayed(tick, 2000);
            }
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
        // Brings the phone's Wi-Fi setup in line with this version (it drops the Wi-Fi suggestions older ones made).
        worker.execute(() -> CameraWifi.apply(WatchService.this));
        cm = getSystemService(ConnectivityManager.class);
        // Also reports a Wi-Fi that is already connected, so a camera joined before boot finished is not missed.
        cm.registerNetworkCallback(Watcher.wifi(), callback);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen, f);
        IntentFilter s = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scans, s, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(scans, s);
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
            unregisterReceiver(scans);
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
