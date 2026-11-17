package dev.olytimesync;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

/** Turns the automatic watch for the camera's Wi-Fi on and off. */
final class Watcher {
    private Watcher() {
    }

    /** Any Wi-Fi network, including one without internet, which is what the camera is. */
    static NetworkRequest wifi() {
        return new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
    }

    /**
     * Three layers, so a phone that kills one still leaves the others: the foreground service that listens for
     * Wi-Fi connections, a system-held callback that reaches NetReceiver even when the service is dead, and the
     * KeepAlive job that restarts the service.
     */
    static void arm(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        try {
            cm.registerNetworkCallback(wifi(), pending(c));
        } catch (RuntimeException e) {
            // the service below is the main path
        }
        KeepAlive.schedule(c);
        startService(c);
    }

    static void startService(Context c) {
        if (WatchService.running) return;
        try {
            Intent service = new Intent(c, WatchService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(service);
            else c.startService(service);
        } catch (RuntimeException e) {
            // Refused from the background on Android 12+ without the battery exemption; the callback still works.
        }
    }

    static void disarm(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        try {
            cm.unregisterNetworkCallback(pending(c));
        } catch (RuntimeException e) {
            // was not registered
        }
        KeepAlive.cancel(c);
        c.stopService(new Intent(c, WatchService.class));
    }

    private static PendingIntent pending(Context c) {
        // Mutable: the system adds the network to the intent when it fires.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(c, 0, new Intent(c, NetReceiver.class), flags);
    }
}
