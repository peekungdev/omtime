package dev.olytimesync;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Everything that keeps the app alive in the background: Android's battery-optimisation exemption, the Android 9+
 * background restriction, and the phone makers' own auto-start and battery-saver pages, which have no standard API
 * and are reached by their component names. A page is only offered when it exists on this phone and is exported.
 */
final class Background {
    /** Auto-start managers, newest first within each maker. Every package here is also listed in the manifest's queries. */
    private static final String[][] AUTOSTART = {
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
            {"com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"},
            {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
            {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
            {"com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"},
    };

    /** The makers' own battery savers, which act on top of Android's battery optimisation. */
    private static final String[][] OEM_BATTERY = {
            {"com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"},
            {"com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"},
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
            {"com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"},
    };

    private Background() {
    }

    /** Exempt from Doze and App Standby. */
    static boolean exempt(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** The user (or the phone) restricted the app's background use: Settings, Battery, Restricted. */
    static boolean restricted(Context c) {
        if (Build.VERSION.SDK_INT < 28) return false;
        ActivityManager am = c.getSystemService(ActivityManager.class);
        return am != null && am.isBackgroundRestricted();
    }

    static boolean allowed(Context c) {
        return exempt(c) && !restricted(c);
    }

    static Intent exemptionRequest(Context c) {
        return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + c.getPackageName()));
    }

    static Intent appDetails(Context c) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
    }

    static Intent autoStartPage(Context c) {
        return find(c, AUTOSTART);
    }

    static Intent oemBatteryPage(Context c) {
        Intent i = find(c, OEM_BATTERY);
        if (i != null) {
            // MIUI's battery saver opens straight on the app's own entry with these.
            i.putExtra("package_name", c.getPackageName());
            i.putExtra("package_label", c.getString(R.string.app_name));
        }
        return i;
    }

    /** "Xiaomi", "Oppo", "Vivo"... for messages that name the phone. */
    static String brand() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        if (m.isEmpty()) return "Android";
        return Character.toUpperCase(m.charAt(0)) + m.substring(1);
    }

    /** Opens the page; if this phone refuses it, falls back to the app's own settings page, then to Settings. */
    static void open(Activity a, Intent page) {
        Intent[] tries = {page, appDetails(a), new Intent(Settings.ACTION_SETTINGS)};
        for (Intent i : tries) {
            if (i == null) continue;
            try {
                a.startActivity(i);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // try the next one
            }
        }
    }

    /** Asks for the exemption; a phone without that dialog gets the full battery-optimisation list instead. */
    static void requestExemption(Activity a) {
        try {
            a.startActivity(exemptionRequest(a));
        } catch (ActivityNotFoundException | SecurityException e) {
            open(a, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private static Intent find(Context c, String[][] pages) {
        for (String[] p : pages) {
            Intent i = new Intent().setComponent(new ComponentName(p[0], p[1]));
            ResolveInfo r;
            try {
                r = c.getPackageManager().resolveActivity(i, 0);
            } catch (RuntimeException e) {
                continue;
            }
            if (r != null && r.activityInfo != null && r.activityInfo.exported) return i;
        }
        return null;
    }
}
