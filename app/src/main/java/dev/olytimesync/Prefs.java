package dev.olytimesync;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Settings, the saved cameras, the last result and a short activity log. */
final class Prefs {
    static final String AUTO = "auto";
    static final String AUTO_CONNECT = "auto_connect";
    static final String CAMERAS = "cameras";
    static final String STATUS = "status";
    static final String LOG = "log";
    /** The maker's auto-start / battery-saver page has been opened; Android cannot tell whether it was switched on. */
    static final String AUTOSTART_SEEN = "autostart_seen";
    static final String OEM_BATTERY_SEEN = "oem_battery_seen";

    /** The last result, for the status card: "ok" or "fail", and what goes with it. */
    static final String LAST_KIND = "last_kind";
    static final String LAST_MODEL = "last_model";
    static final String LAST_SET = "last_set";
    static final String LAST_OFFSET = "last_offset";
    static final String LAST_ERROR = "last_error";
    static final String LAST_AT = "last_at";
    private static final String LAST_OK_MODEL = "last_ok_model";
    private static final String LAST_OK_AT = "last_ok_at";
    private static final int LOG_LINES = 30;

    private Prefs() {
    }

    static SharedPreferences of(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean auto(Context c) {
        return of(c).getBoolean(AUTO, true);
    }

    static boolean autoConnect(Context c) {
        return of(c).getBoolean(AUTO_CONNECT, true);
    }

    static String status(Context c) {
        return of(c).getString(STATUS, c.getString(R.string.status_never));
    }

    static void recordOk(Context c, String model, long set, String offset, String line) {
        long now = System.currentTimeMillis();
        of(c).edit()
                .putString(LAST_KIND, "ok").putString(LAST_MODEL, model).putLong(LAST_SET, set)
                .putString(LAST_OFFSET, offset).putLong(LAST_AT, now)
                .putString(LAST_OK_MODEL, model).putLong(LAST_OK_AT, now)
                .apply();
        record(c, line);
    }

    static void recordFail(Context c, String why, String line) {
        of(c).edit().putString(LAST_KIND, "fail").putString(LAST_ERROR, why)
                .putLong(LAST_AT, System.currentTimeMillis()).apply();
        record(c, line);
    }

    static boolean recentlySynced(Context c, String model, long withinMs) {
        SharedPreferences p = of(c);
        long age = System.currentTimeMillis() - p.getLong(LAST_OK_AT, 0);
        return model.equals(p.getString(LAST_OK_MODEL, null)) && age >= 0 && age < withinMs;
    }

    /** Stores the line as the current status and puts it, time-stamped, at the top of the log. */
    private static synchronized void record(Context c, String line) {
        SharedPreferences p = of(c);
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String[] old = p.getString(LOG, "").split("\n");
        StringBuilder log = new StringBuilder(stamp).append("  ").append(line);
        for (int i = 0; i < old.length && i < LOG_LINES - 1; i++) {
            if (!old[i].isEmpty()) log.append('\n').append(old[i]);
        }
        p.edit().putString(STATUS, line).putString(LOG, log.toString()).apply();
    }
}
