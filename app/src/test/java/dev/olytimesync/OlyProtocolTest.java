package dev.olytimesync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Runs the protocol against a fake camera on localhost and checks what goes over the wire. */
public class OlyProtocolTest {
    private ServerSocket server;
    private OlyProtocol cam;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> arrivals = Collections.synchronizedList(new ArrayList<>());
    private volatile int clockStatus = 200;
    private volatile String caminfo = "<?xml version=\"1.0\"?><caminfo><model>E-PL7</model></caminfo>";

    @Before
    public void start() throws IOException {
        server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    serve(s);
                } catch (IOException e) {
                    // closed, or a broken client
                }
            }
        });
        t.setDaemon(true);
        t.start();
        cam = new OlyProtocol("http://127.0.0.1:" + server.getLocalPort() + "/",
                url -> (HttpURLConnection) url.openConnection());
    }

    private void serve(Socket s) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
        String requestLine = in.readLine();
        if (requestLine == null) return;
        arrivals.add(System.currentTimeMillis());
        String agent = null;
        for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
            if (h.toLowerCase(Locale.US).startsWith("user-agent:")) agent = h.substring(11).trim();
        }
        String uri = requestLine.split(" ")[1];   // exactly as sent, percent-escapes included
        requests.add(uri + " UA=" + agent);
        int code = 200;
        String body = "";
        if (uri.startsWith("/get_caminfo.cgi")) body = caminfo;
        else if (uri.startsWith("/get_commandlist.cgi")) body = "<oishare><cgi name=\"set_utctimediff\"/></oishare>";
        else if (uri.startsWith("/set_utctimediff.cgi")) code = clockStatus;
        else if (!uri.startsWith("/switch_cammode.cgi")) code = 404;
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + code + " X\r\nContent-Type: text/xml\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    @After
    public void stop() throws IOException {
        server.close();
    }

    @Test
    public void readsModelAndIdentifiesAsOiShare() throws IOException {
        assertEquals("E-PL7", cam.model());
        assertEquals("/get_caminfo.cgi UA=OI.Share v2", requests.get(0));
    }

    @Test
    public void somethingElseOnThatAddressIsNotACamera() throws IOException {
        caminfo = "<html><body>router login</body></html>";
        assertNull(cam.model());
    }

    @Test
    public void formatsTimeAndOffsets() {
        long t = 1794909922000L;   // 2026-11-17 10:05:22 UTC
        assertEquals("20261117T100522", OlyProtocol.utcTime(t));
        assertEquals("+0700", OlyProtocol.diff(t, TimeZone.getTimeZone("Asia/Bangkok")));
        assertEquals("+0000", OlyProtocol.diff(t, TimeZone.getTimeZone("UTC")));
        assertEquals("-0500", OlyProtocol.diff(t, TimeZone.getTimeZone("America/New_York")));
        assertEquals("-0400", OlyProtocol.diff(1783000000000L, TimeZone.getTimeZone("America/New_York")));  // DST
        assertEquals("+0545", OlyProtocol.diff(t, TimeZone.getTimeZone("Asia/Kathmandu")));
        assertEquals("-0330", OlyProtocol.diff(t, TimeZone.getTimeZone("America/St_Johns")));
        assertEquals("set_utctimediff.cgi?utctime=20261117T100522&diff=%2B0700",
                OlyProtocol.clockQuery(t, TimeZone.getTimeZone("Asia/Bangkok")));
        assertEquals("set_utctimediff.cgi?utctime=20261117T100522&diff=-0500",
                OlyProtocol.clockQuery(t, TimeZone.getTimeZone("America/New_York")));
    }

    @Test
    public void setsClockOnASecondBoundaryWithTheCurrentTime() throws Exception {
        cam.switchToPlay();
        long before = System.currentTimeMillis();
        long set = cam.setClock(TimeZone.getTimeZone("Asia/Bangkok"), 0);
        long arrived = arrivals.get(arrivals.size() - 1);

        assertEquals(0, set % 1000);
        assertTrue("set time is in the next second or two", set > before && set - before <= 2000);
        // The request reaches the camera at the instant it carries, not up to a second late.
        assertTrue("arrived " + (arrived - set) + " ms after the boundary", arrived - set >= 0 && arrived - set < 400);

        assertEquals("/switch_cammode.cgi?mode=play UA=OI.Share v2", requests.get(0));
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        assertEquals("/set_utctimediff.cgi?utctime=" + f.format(set) + "&diff=%2B0700 UA=OI.Share v2",
                requests.get(1));
    }

    @Test
    public void cameraRefusalIsAnError() {
        clockStatus = 520;
        try {
            cam.setClock(TimeZone.getTimeZone("UTC"), 0);
            fail("expected an IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("HTTP 520"));
        }
    }

    @Test
    public void commandListIsReturned() throws IOException {
        assertTrue(cam.commandList().contains("set_utctimediff"));
    }
}
