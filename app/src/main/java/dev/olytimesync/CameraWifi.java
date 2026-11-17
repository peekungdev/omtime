package dev.olytimesync;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Getting the phone onto the camera's Wi-Fi.
 *
 * Android 10 and later: the cameras are network suggestions, so the phone joins one by itself when nothing better
 * is in range; and while the screen is on, a network request for each camera found with the picker (exact SSID and
 * BSSID) is tried every few minutes. Once the user has approved a camera network, Android grants such requests
 * without asking again, scans at most three times when the camera is not there, and returns the phone to its usual
 * Wi-Fi when the request is released after the sync.
 *
 * Android 6 to 9: the cameras are saved networks; Sync now selects one explicitly. Finding the camera from the
 * background would need location access, so there is no background switch-over there.
 */
final class CameraWifi {
    static final long PROBE_EVERY_MS = 3 * 60 * 1000L;
    private static final long PROBE_MIN_GAP_MS = 30 * 1000L;
    private static final long AFTER_SYNC_MS = 60 * 60 * 1000L;
    private static final long AFTER_FAIL_MS = 10 * 60 * 1000L;
    private static final int PROBE_TIMEOUT_MS = 40 * 1000;
    private static final int USER_TIMEOUT_MS = 60 * 1000;

    /** One join at a time: the probe, Sync now and a newly added camera all use the Wi-Fi radio. */
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static final Map<String, Long> quietUntil = new HashMap<>();
    private static long lastProbe;

    private CameraWifi() {
    }

    /** Whether this Android lets the app move the phone from another Wi-Fi to the camera by itself. */
    static boolean canSwitch() {
        return Build.VERSION.SDK_INT >= 29;
    }

    static boolean autoConnectOn(Context c) {
        return Prefs.auto(c) && Prefs.autoConnect(c);
    }

    private static WifiManager wifi(Context c) {
        return (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    /** Hands the camera list to the phone's Wi-Fi. Call after the list or the switches change, off the main thread. */
    static void apply(Context ctx) {
        Context c = ctx.getApplicationContext();
        WifiManager wm = wifi(c);
        if (wm == null) return;
        if (Build.VERSION.SDK_INT >= 29) applySuggestions(c, wm);
        else applySaved(c, wm);
    }

    private static void applySuggestions(Context c, WifiManager wm) {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            // An empty list removes everything this app suggested before.
            wm.removeNetworkSuggestions(new ArrayList<>());
            if (!autoConnectOn(c)) return;
            List<WifiNetworkSuggestion> list = new ArrayList<>();
            for (Camera cam : Camera.load(c)) {
                list.add(new WifiNetworkSuggestion.Builder()
                        .setSsid(cam.ssid)
                        .setWpa2Passphrase(cam.password)
                        .build());
            }
            if (!list.isEmpty()) wm.addNetworkSuggestions(list);
        } catch (RuntimeException e) {
            // Suggestions are the extra path; the probe and Sync now still work without them.
        }
    }

    @SuppressWarnings("deprecation")
    private static void applySaved(Context c, WifiManager wm) {
        List<Camera> cams = Camera.load(c);
        boolean on = autoConnectOn(c);
        boolean changed = false;
        for (Camera cam : cams) {
            if (cam.netId < 0) {
                cam.netId = addSaved(wm, cam);
                changed = cam.netId >= 0;
            }
            if (cam.netId < 0) continue;
            if (on) wm.enableNetwork(cam.netId, false);
            else wm.disableNetwork(cam.netId);
        }
        if (Build.VERSION.SDK_INT < 26) wm.saveConfiguration();
        if (changed) Camera.save(c, cams);
    }

    // Only reached before Android 10, where reading the saved list needs no location permission; a refusal is caught.
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private static int addSaved(WifiManager wm, Camera cam) {
        WifiConfiguration wc = new WifiConfiguration();
        wc.SSID = "\"" + cam.ssid + "\"";
        wc.preSharedKey = "\"" + cam.password + "\"";
        wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        int id = wm.addNetwork(wc);
        if (id >= 0) return id;
        // Already saved by the user: use that entry.
        try {
            List<WifiConfiguration> saved = wm.getConfiguredNetworks();
            if (saved != null) {
                for (WifiConfiguration s : saved) {
                    if (wc.SSID.equals(s.SSID)) return s.networkId;
                }
            }
        } catch (RuntimeException e) {
            // no access to the list
        }
        return -1;
    }

    /** Called when a camera is removed: forget its saved network (before Android 10). */
    @SuppressWarnings("deprecation")
    static void forget(Context c, Camera cam) {
        if (Build.VERSION.SDK_INT < 29 && cam.netId >= 0) {
            WifiManager wm = wifi(c);
            if (wm != null) {
                wm.removeNetwork(cam.netId);
                if (Build.VERSION.SDK_INT < 26) wm.saveConfiguration();
            }
        }
    }

    /**
     * Background check, from WatchService while the screen is on: if a camera's Wi-Fi is up, join it, sync, and go
     * back. Returns at once; the work runs on its own thread.
     */
    static void probe(Context ctx) {
        if (Build.VERSION.SDK_INT < 29) return;
        Context c = ctx.getApplicationContext();
        if (!autoConnectOn(c) || SyncEngine.cameraNetwork != -1) return;
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm == null || !pm.isInteractive()) return;   // Android pauses these scans while the screen is off
        WifiManager wm = wifi(c);
        if (wm == null || !wm.isWifiEnabled()) return;

        long now = SystemClock.elapsedRealtime();
        List<Camera> targets = new ArrayList<>();
        synchronized (quietUntil) {
            if (lastProbe != 0 && now - lastProbe < PROBE_MIN_GAP_MS) return;
            for (Camera cam : Camera.load(c)) {
                Long until = quietUntil.get(cam.ssid);
                // Only cameras with a known access point that the phone has joined before: Android grants those
                // requests without a dialog.
                if (cam.bssid != null && cam.joined && (until == null || now >= until)) targets.add(cam);
            }
            if (targets.isEmpty() || !busy.compareAndSet(false, true)) return;
            lastProbe = now;
        }
        new Thread(() -> {
            try {
                for (Camera cam : targets) {
                    int r = join(c, cam, false);
                    if (r == SyncEngine.NOT_CAMERA) continue;
                    quiet(cam, r == SyncEngine.OK ? AFTER_SYNC_MS : AFTER_FAIL_MS);
                    if (r == SyncEngine.OK) break;
                }
            } finally {
                busy.set(false);
            }
        }, "camera-probe").start();
    }

    private static void quiet(Camera cam, long ms) {
        synchronized (quietUntil) {
            quietUntil.put(cam.ssid, SystemClock.elapsedRealtime() + ms);
        }
    }

    /** Sync now: the camera the phone is already on, else each saved camera in turn. Blocking. */
    static int syncNow(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (!takeTurn()) return SyncEngine.FAILED;
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            boolean onWifi = false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                onWifi = true;
                int r = SyncEngine.run(c, n, SyncEngine.USER);
                if (r != SyncEngine.NOT_CAMERA) return r;
            }
            List<Camera> cams = Camera.load(c);
            for (Camera cam : cams) {
                int r = join(c, cam, true);
                if (r != SyncEngine.NOT_CAMERA) {
                    if (r == SyncEngine.OK) quiet(cam, AFTER_SYNC_MS);
                    return r;
                }
            }
            int why = !cams.isEmpty() ? R.string.err_camera_not_found
                    : onWifi ? R.string.err_no_camera_added : R.string.err_no_wifi;
            return SyncEngine.failed(c, c.getString(why));
        } finally {
            busy.set(false);
        }
    }

    /** Right after a camera is added: join it and set the clock, so a wrong password shows up straight away. */
    static int syncCamera(Context ctx, Camera cam) {
        Context c = ctx.getApplicationContext();
        if (!takeTurn()) return SyncEngine.FAILED;
        try {
            int r = join(c, cam, true);
            if (r == SyncEngine.NOT_CAMERA) return SyncEngine.failed(c, c.getString(R.string.err_camera_not_found));
            if (r == SyncEngine.OK) quiet(cam, AFTER_SYNC_MS);
            return r;
        } finally {
            busy.set(false);
        }
    }

    private static boolean takeTurn() {
        long end = SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MS + 20000;
        while (!busy.compareAndSet(false, true)) {
            if (SystemClock.elapsedRealtime() > end) return false;
            SystemClock.sleep(250);
        }
        return true;
    }

    private static int join(Context c, Camera cam, boolean user) {
        if (Build.VERSION.SDK_INT >= 29) return joinRequest(c, cam, user);
        return user ? joinSaved(c, cam) : SyncEngine.NOT_CAMERA;
    }

    /** Android 10+: a network request for this camera only; releasing it hands the phone back to its usual Wi-Fi. */
    private static int joinRequest(Context c, Camera cam, boolean user) {
        if (Build.VERSION.SDK_INT < 29) return SyncEngine.NOT_CAMERA;
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        WifiNetworkSpecifier.Builder spec = new WifiNetworkSpecifier.Builder()
                .setSsid(cam.ssid)
                .setWpa2Passphrase(cam.password);
        if (cam.bssid != null) spec.setBssid(MacAddress.fromString(cam.bssid));
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec.build())
                .build();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Network> joined = new AtomicReference<>();
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                joined.compareAndSet(null, network);
                done.countDown();
            }

            @Override
            public void onUnavailable() {
                done.countDown();
            }
        };
        int timeout = user ? USER_TIMEOUT_MS : PROBE_TIMEOUT_MS;
        try {
            cm.requestNetwork(request, callback, timeout);
        } catch (RuntimeException e) {
            return SyncEngine.NOT_CAMERA;
        }
        try {
            done.await(timeout + 5000L, TimeUnit.MILLISECONDS);
            Network n = joined.get();
            if (n == null) return SyncEngine.NOT_CAMERA;
            if (!cam.joined) markJoined(c, cam);
            return SyncEngine.run(c, n, SyncEngine.JOINED | (user ? SyncEngine.USER : 0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SyncEngine.NOT_CAMERA;
        } finally {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (RuntimeException e) {
                // already released
            }
            Network n = joined.get();
            if (n != null && SyncEngine.cameraNetwork == n.getNetworkHandle()) SyncEngine.cameraNetwork = -1;
        }
    }

    private static void markJoined(Context c, Camera cam) {
        cam.joined = true;
        List<Camera> cams = Camera.load(c);
        for (Camera saved : cams) {
            if (saved.ssid.equals(cam.ssid)) {
                saved.joined = true;
                Camera.save(c, cams);
                return;
            }
        }
    }

    /** Android 6 to 9: select the saved camera network, then wait for a camera to answer on the Wi-Fi. */
    @SuppressWarnings("deprecation")
    private static int joinSaved(Context c, Camera cam) {
        WifiManager wm = wifi(c);
        if (wm == null) return SyncEngine.NOT_CAMERA;
        if (cam.netId < 0) {
            cam.netId = addSaved(wm, cam);
            if (cam.netId < 0) return SyncEngine.NOT_CAMERA;
            List<Camera> cams = Camera.load(c);
            Camera.upsert(cams, cam);
            Camera.save(c, cams);
        }
        // disableOthers only lasts until this connection is made; the phone's other networks come back after.
        if (!wm.enableNetwork(cam.netId, true)) return SyncEngine.NOT_CAMERA;
        wm.reconnect();
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Set<Long> tried = new HashSet<>();
        long end = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(1500);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                if (!SyncEngine.cameraSubnetNow(cm, n) || !tried.add(n.getNetworkHandle())) continue;
                int r = SyncEngine.run(c, n, SyncEngine.USER);
                if (r != SyncEngine.NOT_CAMERA) return r;
            }
        }
        return SyncEngine.NOT_CAMERA;
    }
}
