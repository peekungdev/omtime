package dev.olytimesync;

import android.annotation.TargetApi;
import android.app.Activity;
import android.companion.AssociatedDevice;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.companion.WifiDeviceFilter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.os.Build;
import android.widget.Toast;

/**
 * "Find camera": the system's companion-device picker, listing nearby Wi-Fi networks. It needs no location permission,
 * returns the access point's address, and the association it creates lets Android approve the camera's network
 * requests and keep the app running in the background.
 */
final class CameraPicker {
    private CameraPicker() {
    }

    static boolean available(Context c) {
        return Build.VERSION.SDK_INT >= 26
                && c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP);
    }

    @TargetApi(26)
    static void start(Activity a, int requestCode) {
        CompanionDeviceManager cdm = a.getSystemService(CompanionDeviceManager.class);
        // Only Olympus / OM System camera networks are listed; anything else can still be typed in by hand.
        AssociationRequest request = new AssociationRequest.Builder()
                .addDeviceFilter(new WifiDeviceFilter.Builder().setNamePattern(Camera.SSID_PATTERN).build())
                .setSingleDevice(false)
                .build();
        cdm.associate(request, new CompanionDeviceManager.Callback() {
            @Override
            @SuppressWarnings("deprecation")
            public void onDeviceFound(IntentSender chooser) {
                try {
                    a.startIntentSenderForResult(chooser, requestCode, null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                    onFailure(e.getMessage());
                }
            }

            @Override
            public void onFailure(CharSequence error) {
                if (a.isFinishing()) return;
                Toast.makeText(a, a.getString(R.string.err_find_failed, error == null ? "" : error),
                        Toast.LENGTH_LONG).show();
            }
        }, null);
    }

    /** {ssid, bssid} picked by the user, or null. The bssid may be null. */
    @SuppressWarnings("deprecation")
    static String[] result(Intent data) {
        if (data == null || Build.VERSION.SDK_INT < 26) return null;
        Object device = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE);
        if (device instanceof ScanResult) return of((ScanResult) device);
        if (Build.VERSION.SDK_INT >= 34) {
            AssociationInfo info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION,
                    AssociationInfo.class);
            AssociatedDevice ad = info == null ? null : info.getAssociatedDevice();
            if (ad != null && ad.getWifiDevice() != null) return of(ad.getWifiDevice());
        }
        if (Build.VERSION.SDK_INT >= 33) {
            AssociationInfo info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION);
            if (info != null && info.getDisplayName() != null) {
                MacAddress mac = info.getDeviceMacAddress();
                return new String[]{info.getDisplayName().toString(), mac == null ? null : mac.toString()};
            }
        }
        return null;
    }

    private static String[] of(ScanResult r) {
        return new String[]{r.SSID, r.BSSID};
    }

    /** Drops the association when the camera is removed. */
    @SuppressWarnings("deprecation")
    static void forget(Context c, Camera cam) {
        if (!available(c) || cam.bssid == null) return;
        try {
            c.getSystemService(CompanionDeviceManager.class).disassociate(cam.bssid);
        } catch (RuntimeException e) {
            // was not associated (typed in by hand, or already gone)
        }
    }
}
