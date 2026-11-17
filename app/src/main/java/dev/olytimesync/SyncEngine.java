package dev.olytimesync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Decides whether a Wi-Fi network is an Olympus camera and, if it is, sets the camera's clock.
 *
 * Detection needs no location permission: the camera is an access point that hands out 192.168.0.x addresses and
 * answers get_caminfo.cgi on 192.168.0.10. Every request is bound to that network, because Android keeps mobile
 * data as the default route while it is on a Wi-Fi with no internet.
 */
final class SyncEngine {
    static final int NOT_CAMERA = 0;
    static final int OK = 1;
    static final int FAILED = 2;

    /** Flag: the user asked (Sync now). Always syncs again, and stays quiet when there is no camera. */
    static final int USER = 1;
    /** Flag: the app joined this network to reach a camera, so no camera there is an error worth reporting. */
    static final int JOINED = 2;

    /**
     * An automatic sync of the same camera within this time is skipped. It covers only the phone rejoining the
     * camera's Wi-Fi by itself right after the probe released it; switching the camera's Wi-Fi off and on again
     * takes longer than this and is synced again.
     */
    private static final long REPEAT_MS = 2 * 60 * 1000L;
    private static final String TAG = CameraWifi.TAG;

    private static final Object LOCK = new Object();
    /** The connection already dealt with, so the listeners and the joiner do not all sync it. */
    private static long handled = -1;
    private static int handledResult = NOT_CAMERA;
    /** The network a camera answered on, or -1; the background probe leaves the phone alone while it is on one. */
    static volatile long cameraNetwork = -1;

    private SyncEngine() {
    }

    static String name(int result) {
        return result == OK ? "OK" : result == FAILED ? "FAILED" : "NOT_CAMERA";
    }

    static int run(Context context, Network net, int flags) {
        return run(context, net, flags, null);
    }

    /** camera: the saved camera the app joined this network for, or null. */
    static int run(Context context, Network net, int flags, Camera camera) {
        Context c = context.getApplicationContext();
        boolean user = (flags & USER) != 0;
        boolean joined = (flags & JOINED) != 0;
        synchronized (LOCK) {
            long handle = net.getNetworkHandle();
            if (handle == handled) {
                if (joined && handledResult == OK) return OK;
                if (!joined && !user) return NOT_CAMERA;
            }
            int result;
            try {
                result = sync(c, net, user, joined, camera);
            } catch (RuntimeException e) {
                // e.g. the network went away mid-way and the platform threw instead of failing the request
                Log.w(TAG, "sync threw", e);
                result = (user || joined) ? failed(c, String.valueOf(e.getMessage())) : NOT_CAMERA;
            }
            if (result != FAILED) {
                handled = handle;
                handledResult = result;
            }
            return result;
        }
    }

    private static int sync(Context c, Network net, boolean user, boolean joined, Camera camera) {
        if (!onCameraSubnet(c, net)) {
            if (joined) Log.i(TAG, "joined network has no 192.168.0.x address");
            return joined ? failed(c, c.getString(R.string.err_no_camera)) : NOT_CAMERA;
        }
        Log.i(TAG, "camera subnet on network " + net.getNetworkHandle() + (user ? " (user)" : joined ? " (joined)" : " (auto)"));

        OlyProtocol cam = new OlyProtocol(OlyProtocol.CAMERA_BASE, url -> (HttpURLConnection) net.openConnection(url));
        cam.timeoutMs = 2500;

        // The camera's web server can come up a moment after the Wi-Fi link does.
        String model = null;
        long rtt = 0;
        int attempts = user && !joined ? 2 : 4;
        for (int i = 0; i < attempts && model == null; i++) {
            if (i > 0) SystemClock.sleep(1500);
            long t0 = SystemClock.elapsedRealtime();
            try {
                model = cam.model();
                rtt = SystemClock.elapsedRealtime() - t0;
                if (model == null) break;   // something answered, and it is not a camera
            } catch (IOException e) {
                // nothing there yet
            }
        }
        if (model == null) {
            Log.i(TAG, "no camera answered get_caminfo");
            return joined ? failed(c, c.getString(R.string.err_no_camera)) : NOT_CAMERA;
        }
        Log.i(TAG, "camera " + model + ", round trip " + rtt + " ms");
        cameraNetwork = net.getNetworkHandle();
        if (!user && !joined && Prefs.recentlySynced(c, model, REPEAT_MS)) {
            Log.i(TAG, model + " was synced moments ago; skipping");
            CameraWifi.synced(c, model, camera);
            return OK;
        }

        cam.timeoutMs = 5000;
        String commands = null;
        try {
            commands = cam.commandList();
        } catch (IOException e) {
            // not fatal: try the clock command anyway
        }
        if (commands != null && !commands.contains("set_utctimediff")) {
            Log.i(TAG, model + " has no set_utctimediff in its command list");
            return failed(c, c.getString(R.string.err_unsupported, model));
        }

        TimeZone tz = TimeZone.getDefault();
        IOException last = null;
        for (int i = 0; i < 2; i++) {
            try {
                if (i > 0) SystemClock.sleep(1000);
                try {
                    cam.switchToPlay();   // the clock command is accepted in play mode
                } catch (IOException e) {
                    // let the clock command itself report the problem
                }
                long set = cam.setClock(tz, Math.min(rtt / 2, 250));
                OlyProtocol.Reply r = cam.last;
                Log.i(TAG, "set_utctimediff -> HTTP " + r.code + " body \"" + r.body.trim() + "\"");
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                String offset = offsetLabel(tz, set);
                String line = c.getString(R.string.status_ok, model, f.format(new Date(set)), offset);
                Prefs.recordOk(c, model, set, offset, line);
                Notes.result(c, line);
                CameraWifi.synced(c, model, camera);
                return OK;
            } catch (IOException e) {
                Log.w(TAG, "setting the clock failed: " + e.getMessage());
                last = e;
            }
        }
        return failed(c, model + ": " + last.getMessage());
    }

    /** Records and notifies a failure; returns FAILED. */
    static int failed(Context c, String why) {
        String line = c.getString(R.string.status_fail, why);
        Prefs.recordFail(c, why, line);
        Notes.result(c, line);
        return FAILED;
    }

    /** True once the network has a 192.168.0.x address; waits a few seconds for DHCP to finish. */
    private static boolean onCameraSubnet(Context c, Network net) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        for (int i = 0; i < 12; i++) {
            LinkProperties lp = cm.getLinkProperties(net);
            if (lp != null) {
                Boolean v = cameraSubnet(lp);
                if (v != null) return v;
            }
            SystemClock.sleep(500);
        }
        return false;
    }

    /** The same check without waiting. */
    static boolean cameraSubnetNow(ConnectivityManager cm, Network net) {
        LinkProperties lp = cm.getLinkProperties(net);
        return lp != null && Boolean.TRUE.equals(cameraSubnet(lp));
    }

    /** True or false once the network has an IPv4 address, null before. */
    private static Boolean cameraSubnet(LinkProperties lp) {
        boolean anyV4 = false;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (!(a instanceof Inet4Address)) continue;
            anyV4 = true;
            byte[] b = a.getAddress();
            if ((b[0] & 0xff) == 192 && (b[1] & 0xff) == 168 && b[2] == 0) return true;
        }
        return anyV4 ? Boolean.FALSE : null;
    }

    /** "UTC+07:00" */
    static String offsetLabel(TimeZone tz, long at) {
        String d = OlyProtocol.diff(at, tz);
        return "UTC" + d.substring(0, 3) + ":" + d.substring(3);
    }
}
