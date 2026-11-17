package dev.olytimesync;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    static final String TAG = "OMTime";
    static final long PROBE_EVERY_MS = 3 * 60 * 1000L;
    private static final long PROBE_MIN_GAP_MS = 30 * 1000L;
    /**
     * After a sync the camera is left alone this long. Seen on a PEN E-PL7: it switches its Wi-Fi off about a minute
     * after the phone leaves, so once this has passed, its Wi-Fi being there again means it was switched on again.
     * The app often cannot watch it go off (no checks with the screen off), so it does not wait to see that.
     */
    private static final long HOLD_MS = 3 * 60 * 1000L;
    /**
     * A camera synced again within STAYS_ON_MS of its last sync, without being seen going off in between, probably
     * keeps its Wi-Fi on; it is then left alone this long, so the phone is not pulled onto it every few minutes.
     */
    private static final long LONG_HOLD_MS = 30 * 60 * 1000L;
    private static final long STAYS_ON_MS = 15 * 60 * 1000L;
    private static final long AFTER_FAIL_MS = 10 * 60 * 1000L;
    private static final long AFTER_NOT_FOUND_MS = 60 * 1000L;
    /** While the screen is on, a fresh Wi-Fi scan is asked for this often (Android itself scans rarely on a good Wi-Fi). */
    static final long SCAN_EVERY_MS = 2 * 60 * 1000L;
    /** If the phone's newest scan result is older than this, the scan list says nothing about the camera. */
    private static final long SCANS_STALE_MS = 3 * 60 * 1000L;
    /** The camera counts as switched on when a scan saw it this recently... */
    private static final long SEEN_MS = 90 * 1000L;
    /** ...and as switched off when recent scans have not seen it for this long. Tolerates a scan that missed it. */
    private static final long GONE_MS = 150 * 1000L;
    private static final long NEVER = Long.MAX_VALUE;
    private static final int PROBE_TIMEOUT_MS = 40 * 1000;
    private static final int USER_TIMEOUT_MS = 60 * 1000;

    /** One join at a time: the probe, Sync now and a newly added camera all use the Wi-Fi radio. */
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static final Map<String, Long> quietUntil = new HashMap<>();
    /** Per camera (by network name): when it was last synced, until when it is left alone, and whether a scan has
     *  shown its Wi-Fi off since that sync. Guarded by quietUntil. */
    private static final Map<String, Long> lastSyncAt = new HashMap<>();
    private static final Map<String, Long> holdUntil = new HashMap<>();
    private static final Set<String> offSinceSync = new HashSet<>();
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

    /**
     * Android 10+ no longer gets the cameras as Wi-Fi suggestions (1.2 to 1.6 did). Seen on a real phone: after the
     * app released the camera, Android joined it again through the suggestion as the phone's main Wi-Fi and stayed
     * on the camera, with no internet, for minutes. The app's own join-sync-release covers the same ground, so this
     * only removes what older versions suggested.
     */
    private static void applySuggestions(Context c, WifiManager wm) {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            // An empty list removes everything this app suggested before.
            wm.removeNetworkSuggestions(new ArrayList<WifiNetworkSuggestion>());
        } catch (RuntimeException e) {
            // nothing was suggested
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
     * Background check: if a camera's Wi-Fi is up and that camera has not been synced since its Wi-Fi came on, join
     * it, sync, and go back. Called by WatchService on every Wi-Fi scan result (fromScan) and on a timer while the
     * screen is on. Returns at once; the work runs on its own thread.
     */
    static void probe(Context ctx, boolean fromScan) {
        if (Build.VERSION.SDK_INT < 29) return;
        Context c = ctx.getApplicationContext();
        if (!autoConnectOn(c)) return;
        if (SyncEngine.cameraNetwork != -1) return;   // already on a camera's Wi-Fi; the listener handles it
        PowerManager pm = c.getSystemService(PowerManager.class);
        // Without fresh scan results, the network request would have to scan, and Android pauses that with the screen off.
        if (!fromScan && (pm == null || !pm.isInteractive())) return;
        WifiManager wm = wifi(c);
        if (wm == null || !wm.isWifiEnabled()) return;

        // How long ago each network was last seen; null when the app may not read scans or they are out of date.
        Map<String, Long> ages = scanAges(c, wm);
        if (fromScan && ages == null) return;
        long now = SystemClock.elapsedRealtime();
        List<Camera> targets = new ArrayList<>();
        synchronized (quietUntil) {
            for (Camera cam : Camera.load(c)) {
                // Only cameras the phone has joined through the app before: Android grants those without a dialog.
                // A typed-in camera has no access point address, which older Android versions need for that.
                if (!cam.joined || (cam.bssid == null && Build.VERSION.SDK_INT < 33)) continue;
                if (ages != null) {
                    long age = Math.min(age(ages, cam.ssid), cam.bssid == null ? NEVER : age(ages, cam.bssid));
                    if (age > GONE_MS) {
                        // Seen going off: the session is over, so the next time it is on it syncs straight away.
                        if (lastSyncAt.containsKey(cam.ssid) && offSinceSync.add(cam.ssid)) {
                            holdUntil.remove(cam.ssid);
                            Log.i(TAG, cam.ssid + " is off; it syncs again when it comes back");
                        }
                        continue;
                    }
                    if (age > SEEN_MS) continue;    // not in the latest scans; wait until one sees it
                }
                Long hold = holdUntil.get(cam.ssid);
                if (hold != null && now < hold) continue;   // synced moments ago
                Long until = quietUntil.get(cam.ssid);
                if (until == null || now >= until) targets.add(cam);
            }
            if (targets.isEmpty()) return;
            if (lastProbe != 0 && now - lastProbe < PROBE_MIN_GAP_MS) return;
            if (!busy.compareAndSet(false, true)) return;
            lastProbe = now;
        }
        Log.i(TAG, "probe: " + targets.size() + " camera(s) " + (ages != null ? "seen in Wi-Fi scan" : "on timer"));
        new Thread(() -> {
            try {
                for (Camera cam : targets) {
                    int r = join(c, cam, false);
                    Log.i(TAG, "probe " + cam.ssid + ": " + SyncEngine.name(r));
                    if (r == SyncEngine.OK) break;
                    quiet(cam, r == SyncEngine.FAILED ? AFTER_FAIL_MS : AFTER_NOT_FOUND_MS);
                }
            } finally {
                busy.set(false);
            }
        }, "camera-probe").start();
    }

    private static boolean canReadScans(Context c) {
        return Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * For each network name and access point address in the phone's Wi-Fi scan list, how many milliseconds ago it
     * was last seen. Null when the app may not read scans, or when the phone has not scanned for a while, so the
     * list says nothing about what is on now. Android 13+ allows reading scans with "Nearby devices" (declared
     * neverForLocation), so no location permission.
     */
    @SuppressLint("MissingPermission")
    private static Map<String, Long> scanAges(Context c, WifiManager wm) {
        if (!canReadScans(c)) return null;
        try {
            List<ScanResult> results = wm.getScanResults();
            if (results == null || results.isEmpty()) return null;
            long nowUs = SystemClock.elapsedRealtime() * 1000L;
            long newest = NEVER;
            Map<String, Long> ages = new HashMap<>();
            for (ScanResult r : results) {
                long age = Math.max(0, (nowUs - r.timestamp) / 1000L);   // timestamp: microseconds since boot
                newest = Math.min(newest, age);
                @SuppressWarnings("deprecation") String ssid = r.SSID;
                if (ssid != null && age < age(ages, ssid)) ages.put(ssid, age);
                if (r.BSSID != null) ages.put(r.BSSID.toLowerCase(Locale.US), age);
            }
            return newest > SCANS_STALE_MS ? null : ages;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long age(Map<String, Long> ages, String key) {
        Long a = ages.get(key);
        return a == null ? NEVER : a;
    }

    /**
     * Asks Android for a fresh Wi-Fi scan; the results reach probe() through WatchService's scan receiver. False when
     * that is not possible (no scan access, Wi-Fi off, or Android's scan throttling said no).
     */
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    static boolean requestScan(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (!autoConnectOn(c) || !canReadScans(c) || SyncEngine.cameraNetwork != -1) return false;
        WifiManager wm = wifi(c);
        if (wm == null || !wm.isWifiEnabled()) return false;
        try {
            boolean ok = wm.startScan();
            Log.i(TAG, "scan requested: " + ok);
            return ok;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void quiet(Camera cam, long ms) {
        synchronized (quietUntil) {
            quietUntil.put(cam.ssid, SystemClock.elapsedRealtime() + ms);
        }
    }

    /**
     * A camera was synced (by the probe, Sync now, or the phone joining its Wi-Fi by itself). Holds off the camera it
     * was, and every saved camera whose network name starts with the model ("E-PL7" for "E-PL7-P-..."), for a while.
     */
    static void synced(Context c, String model, Camera joined) {
        long now = SystemClock.elapsedRealtime();
        synchronized (quietUntil) {
            Set<String> names = new HashSet<>();
            if (joined != null) names.add(joined.ssid);
            for (Camera cam : Camera.load(c)) {
                if (cam.ssid.startsWith(model + "-")) names.add(cam.ssid);
            }
            for (String ssid : names) {
                Long prev = lastSyncAt.get(ssid);
                boolean staysOn = prev != null && now - prev < STAYS_ON_MS && !offSinceSync.contains(ssid);
                lastSyncAt.put(ssid, now);
                holdUntil.put(ssid, now + (staysOn ? LONG_HOLD_MS : HOLD_MS));
                offSinceSync.remove(ssid);
                Log.i(TAG, ssid + " synced; left alone for " + (staysOn ? "30 min (its Wi-Fi seems to stay on)" : "3 min"));
            }
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
                if (r != SyncEngine.NOT_CAMERA) return r;
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
        Log.i(TAG, "requesting " + cam.ssid + (cam.bssid != null ? " / " + cam.bssid : "") + (user ? " (user)" : ""));
        try {
            cm.requestNetwork(request, callback, timeout);
        } catch (RuntimeException e) {
            Log.w(TAG, "network request refused", e);
            return SyncEngine.NOT_CAMERA;
        }
        try {
            done.await(timeout + 5000L, TimeUnit.MILLISECONDS);
            Network n = joined.get();
            if (n == null) {
                Log.i(TAG, cam.ssid + ": not joined (not in range, or the request was not approved)");
                return SyncEngine.NOT_CAMERA;
            }
            Log.i(TAG, cam.ssid + ": joined");
            if (!cam.joined) markJoined(c, cam);
            return SyncEngine.run(c, n, SyncEngine.JOINED | (user ? SyncEngine.USER : 0), cam);
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
