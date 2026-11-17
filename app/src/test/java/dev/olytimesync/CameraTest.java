package dev.olytimesync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CameraTest {
    @Test
    public void listSurvivesSaveAndLoad() {
        List<Camera> list = new ArrayList<>();
        list.add(new Camera("E-PL7-P-BHT123456", "12345678", "A0:B1:C2:D3:E4:F5", -1));
        list.add(new Camera("TG-6 \"quoted\" name", "pa\\ss\"word with spaces", null, 7));
        list.get(0).joined = true;

        List<Camera> back = Camera.fromJson(Camera.toJson(list));
        assertTrue(back.get(0).joined);
        assertEquals(false, back.get(1).joined);
        assertEquals(2, back.size());
        assertEquals("E-PL7-P-BHT123456", back.get(0).ssid);
        assertEquals("12345678", back.get(0).password);
        assertEquals("a0:b1:c2:d3:e4:f5", back.get(0).bssid);
        assertEquals(-1, back.get(0).netId);
        assertEquals("TG-6 \"quoted\" name", back.get(1).ssid);
        assertEquals("pa\\ss\"word with spaces", back.get(1).password);
        assertNull(back.get(1).bssid);
        assertEquals(7, back.get(1).netId);
    }

    @Test
    public void findCameraListsOnlyOlympusAndOmNetworks() {
        String[] cameras = {
                "E-PL8-P-00000001",        // the E-PL8 manual's example
                "E-M1MK2-P-12345678",      // the E-M1 Mark II manual's X-XXXXX-X-XXXXXXXX layout
                "E-PL7-P-BHT123456", "E-M1-P-BHM502355", "E-M10MarkIV-P-12345678", "E-P7-P-12345678",
                "PEN-F-P-12345678", "OM-1-P-12345678", "OM-5-P-ABCD1234", "TG-7-P-12345678", "TG-6-P-12345678",
                "SH-2-P-12345678", "STYLUS1s-P-12345678", "AIR-A01-P-12345678",
        };
        for (String s : cameras) assertTrue(s, Camera.looksLikeCamera(s));
        String[] others = {
                "HomeWiFi", "TP-Link_5G", "TP-LINK_A1B2", "DIRECT-xy-Android", "AndroidAP_1234", "E-Mail-Router",
                "True_H_5G", "AIS 4G Hi-Speed Home WiFi", "OPPO A78", "E-PL7", "", "e-pl7-p-12345678",
        };
        for (String s : others) assertEquals(s, false, Camera.looksLikeCamera(s));
        assertEquals(false, Camera.looksLikeCamera(null));
    }

    @Test
    public void unreadableListIsEmpty() {
        assertTrue(Camera.fromJson("").isEmpty());
        assertTrue(Camera.fromJson(null).isEmpty());
        assertTrue(Camera.fromJson("not json").isEmpty());
    }

    @Test
    public void checksNameAndPassword() {
        assertEquals(0, Camera.problem("E-M1-P-1", "12345678"));
        assertEquals(R.string.err_password, Camera.problem("E-M1-P-1", "1234567"));
        assertEquals(R.string.err_password, Camera.problem("E-M1-P-1", "x".repeat(64)));
        assertEquals(0, Camera.problem("E-M1-P-1", "x".repeat(63)));
        assertEquals(R.string.err_ssid, Camera.problem("   ", "12345678"));
        assertEquals(R.string.err_ssid, Camera.problem("x".repeat(33), "12345678"));
        assertEquals(0, Camera.problem("x".repeat(32), "12345678"));
    }

    @Test
    public void acceptsOnlyRealAccessPointAddresses() {
        assertEquals("a0:b1:c2:d3:e4:f5", Camera.normalBssid(" A0:B1:C2:D3:E4:F5 "));
        assertNull(Camera.normalBssid("02:00:00:00:00:00"));   // what Android reports when it hides the address
        assertNull(Camera.normalBssid("00:00:00:00:00:00"));
        assertNull(Camera.normalBssid("a0-b1-c2-d3-e4-f5"));
        assertNull(Camera.normalBssid(null));
    }

    @Test
    public void addingTheSameNetworkReplacesItAndKeepsItsSavedId() {
        List<Camera> list = new ArrayList<>();
        list.add(new Camera("E-PL7-P-1", "11111111", "a0:b1:c2:d3:e4:f5", 4));
        list.get(0).joined = true;
        Camera.upsert(list, new Camera("E-PL7-P-1", "22222222", "a0:b1:c2:d3:e4:f5", -1));
        assertTrue("same access point: still approved", list.get(0).joined);
        Camera.upsert(list, new Camera("E-PL7-P-1", "22222222", "a0:b1:c2:d3:e4:f6", -1));
        assertEquals("different access point: approve again", false, list.get(0).joined);
        Camera.upsert(list, new Camera("E-M1-P-2", "33333333", null, -1));
        assertEquals(2, list.size());
        assertEquals("22222222", list.get(0).password);
        assertEquals(4, list.get(0).netId);
        assertEquals("a0:b1:c2:d3:e4:f6", list.get(0).bssid);
    }
}
