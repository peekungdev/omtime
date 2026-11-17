package dev.olytimesync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The slice of the Olympus Wi-Fi protocol (the one OI.Share speaks) needed to set the camera clock.
 * Plain Java on purpose, so it runs in JVM tests against a fake camera.
 *
 * The camera is its own access point and always answers on 192.168.0.10. The clock command is
 * set_utctimediff.cgi?utctime=yyyyMMddTHHmmss&diff=+hhmm, sent in play mode.
 */
public final class OlyProtocol {

    /** Opens the connection; on Android this binds it to the camera's Wi-Fi network. */
    public interface Opener {
        HttpURLConnection open(URL url) throws IOException;
    }

    public static final String CAMERA_BASE = "http://192.168.0.10/";
    private static final Pattern MODEL = Pattern.compile("<model>\\s*([^<]+?)\\s*</model>");
    private static final int MAX_BODY = 512 * 1024;

    public static final class Reply {
        public final int code;
        public final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body;
        }

        public boolean ok() {
            return code == 200 || code == 202;
        }
    }

    private final String base;
    private final Opener opener;
    public int timeoutMs = 3000;
    /** The most recent reply, for logging. */
    public volatile Reply last;

    public OlyProtocol(String base, Opener opener) {
        this.base = base;
        this.opener = opener;
    }

    public Reply get(String pathAndQuery) throws IOException {
        HttpURLConnection c = opener.open(new URL(base + pathAndQuery));
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            // The camera refuses clients that do not introduce themselves as its own app.
            c.setRequestProperty("User-Agent", "OI.Share v2");
            c.setRequestProperty("Connection", "close");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            last = new Reply(code, in == null ? "" : read(in));
            return last;
        } finally {
            c.disconnect();
        }
    }

    /** The camera model ("E-PL7"), or null when whatever answered is not an Olympus camera. */
    public String model() throws IOException {
        Reply r = get("get_caminfo.cgi");
        if (!r.ok() || !r.body.contains("<caminfo")) return null;
        Matcher m = MODEL.matcher(r.body);
        return m.find() ? m.group(1) : null;
    }

    /** The XML list of commands this body supports. */
    public String commandList() throws IOException {
        Reply r = get("get_commandlist.cgi");
        if (!r.ok()) throw new IOException("get_commandlist: HTTP " + r.code);
        return r.body;
    }

    public void switchToPlay() throws IOException {
        Reply r = get("switch_cammode.cgi?mode=play");
        if (!r.ok()) throw new IOException("switch_cammode: HTTP " + r.code);
    }

    /**
     * Sets the camera clock and returns the instant (epoch ms) it was set to. The camera keeps whole seconds, so
     * the request is timed to land on a second boundary; leadMs is how early to send it (about half a round trip).
     */
    public long setClock(TimeZone tz, long leadMs) throws IOException {
        long now = System.currentTimeMillis();
        long target = (now / 1000 + 1) * 1000;
        if (target - leadMs < now) target += 1000;
        try {
            Thread.sleep(Math.max(0, target - leadMs - now));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }
        Reply r = get(clockQuery(target, tz));
        if (!r.ok()) {
            String detail = r.body.replaceAll("\\s+", " ").trim();
            if (detail.length() > 80) detail = detail.substring(0, 80);
            throw new IOException("set_utctimediff: HTTP " + r.code + (detail.isEmpty() ? "" : " " + detail));
        }
        return target;
    }

    static String clockQuery(long epochMs, TimeZone tz) {
        // The sign is percent-encoded: a bare '+' in a query string means a space.
        return "set_utctimediff.cgi?utctime=" + utcTime(epochMs) + "&diff=" + diff(epochMs, tz).replace("+", "%2B");
    }

    static String utcTime(long epochMs) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(epochMs));
    }

    /** The zone's offset from UTC at that instant, daylight saving included: "+0700", "-0330". */
    static String diff(long epochMs, TimeZone tz) {
        int minutes = tz.getOffset(epochMs) / 60000;
        int abs = Math.abs(minutes);
        return String.format(Locale.US, "%s%02d%02d", minutes < 0 ? "-" : "+", abs / 60, abs % 60);
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (out.size() < MAX_BODY && (n = s.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
