package dev.olytimesync;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** A camera's Wi-Fi: the network name and password it shows, and its access point address when the picker gave it. */
final class Camera {
    /**
     * The network names Olympus and OM System cameras use: model, a one-letter mode, then a serial, such as
     * "E-PL8-P-00000001" or "E-M1MK2-P-12345678". The model must start like an Olympus/OM body (OM-D/PEN "E-M"/"E-P",
     * PEN-F, OM-, TG-, SH-, STYLUS, SP-, AIR, XZ-), so ordinary routers and phone hotspots are left out.
     */
    static final Pattern SSID_PATTERN = Pattern.compile(
            "^(E-[MP][A-Za-z0-9]*|PEN[A-Za-z0-9-]*|OM-[A-Za-z0-9]+|TG-[A-Za-z0-9]+|SH-[A-Za-z0-9]+"
                    + "|STYLUS[A-Za-z0-9-]*|SP-[A-Za-z0-9]+|AIR-?[A-Za-z0-9]*|XZ-[A-Za-z0-9]+)-[A-Z]-[A-Za-z0-9]{4,}$");

    static boolean looksLikeCamera(String ssid) {
        return ssid != null && SSID_PATTERN.matcher(ssid).matches();
    }

    final String ssid;
    final String password;
    /** Lower-case "aa:bb:cc:dd:ee:ff", or null when the network was typed in by hand. */
    final String bssid;
    /** The phone's saved-network id for it before Android 10, or -1. */
    int netId;
    /**
     * The phone has joined this network through the app at least once, so the user has approved it and Android
     * grants later requests without a dialog. Background switching waits for this, or a declined first approval
     * would bring the dialog back every few minutes.
     */
    boolean joined;

    Camera(String ssid, String password, String bssid, int netId) {
        this.ssid = ssid;
        this.password = password;
        this.bssid = normalBssid(bssid);
        this.netId = netId;
    }

    static List<Camera> load(Context c) {
        return fromJson(Prefs.of(c).getString(Prefs.CAMERAS, ""));
    }

    static void save(Context c, List<Camera> list) {
        Prefs.of(c).edit().putString(Prefs.CAMERAS, toJson(list)).apply();
    }

    /** Adds the camera, or replaces the one with the same network name (keeping its saved-network id). */
    static void upsert(List<Camera> list, Camera cam) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).ssid.equals(cam.ssid)) {
                if (cam.netId < 0) cam.netId = list.get(i).netId;
                if (cam.bssid == null || cam.bssid.equals(list.get(i).bssid)) cam.joined |= list.get(i).joined;
                list.set(i, cam);
                return;
            }
        }
        list.add(cam);
    }

    /** 0 when the pair is usable, otherwise the string resource that says what is wrong. */
    static int problem(String ssid, String password) {
        int ssidBytes = ssid.getBytes(StandardCharsets.UTF_8).length;
        if (ssid.trim().isEmpty() || ssidBytes > 32) return R.string.err_ssid;
        if (password.length() < 8 || password.length() > 63) return R.string.err_password;
        return 0;
    }

    /** A usable access point address, or null (including the placeholder Android reports when it hides one). */
    static String normalBssid(String b) {
        if (b == null) return null;
        b = b.trim().toLowerCase(Locale.US);
        if (!b.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}")) return null;
        if (b.equals("00:00:00:00:00:00") || b.equals("02:00:00:00:00:00") || b.equals("ff:ff:ff:ff:ff:ff")) return null;
        return b;
    }

    static String toJson(List<Camera> list) {
        JSONArray a = new JSONArray();
        try {
            for (Camera cam : list) {
                JSONObject o = new JSONObject();
                o.put("ssid", cam.ssid);
                o.put("password", cam.password);
                if (cam.bssid != null) o.put("bssid", cam.bssid);
                o.put("netId", cam.netId);
                o.put("joined", cam.joined);
                a.put(o);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return a.toString();
    }

    static List<Camera> fromJson(String s) {
        List<Camera> list = new ArrayList<>();
        if (s == null || s.isEmpty()) return list;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Camera cam = new Camera(o.getString("ssid"), o.getString("password"),
                        o.has("bssid") ? o.getString("bssid") : null, o.optInt("netId", -1));
                cam.joined = o.optBoolean("joined", false);
                list.add(cam);
            }
        } catch (JSONException e) {
            list.clear();
        }
        return list;
    }
}
