# OM Time

**Automatic clock sync for Olympus and OM System Wi-Fi cameras, from your Android phone.**

Camera clocks drift, and they don't change time zone when you travel. OM Time fixes that without
any effort: when the camera's Wi-Fi is switched on, the phone connects to it, sets the camera clock
from the phone's time and time zone, and goes back to its usual Wi-Fi. It runs from the moment the phone
starts, so there is nothing to open.

Made by **Peekungdev | peekung-development**

---

## Features

- **Automatic sync**: sets the camera clock once each time the camera's Wi-Fi is switched on, with
  time zone and daylight saving taken from the phone.
- **Auto-connect**: joins the camera's Wi-Fi by itself, even from another Wi-Fi network, then returns
  the phone to that network straight away.
- **Starts with the phone**: runs in the background after a restart, with guided setup for battery
  optimisation and the auto-start settings that Xiaomi, Oppo, Vivo, Huawei and other phones need.
- **Find camera**: lists only Olympus / OM System camera networks. There is also a manual
  network-name and password entry.
- **Accurate to the second**: the request is timed to land on a second boundary, so the camera shows
  the same second as the phone.
- **Several cameras**: keep all of your bodies in one list.
- **No location permission, no internet, no tracking**: the app only talks to the camera.
- **English and Thai**, light and dark theme.
- **Small**: about 80 KB, with no third-party libraries.

## Requirements

- Android 6.0 or newer, 32-bit or 64-bit (one APK for both).
- An Olympus or OM System camera with Wi-Fi smartphone connection (the cameras that work with the
  OI.Share / OM Image Share app), for example the PEN E-PL7 to E-PL10, PEN E-P7, PEN-F, OM-D E-M1 / E-M5 /
  E-M10 series, OM-1, OM-5 and the Tough TG series.

## Install

1. Download `OMTime-<version>.apk` from [Releases](../../releases).
2. Open it on the phone and allow installing from that source when asked.

From a computer with ADB, `install.ps1` installs the APK and also grants notifications and background
running, so the phone asks fewer questions:

```powershell
.\install.ps1            # one phone connected with USB debugging
.\install.ps1 -Serial X  # several devices; X from "adb devices"
```

## First-time setup

1. Open OM Time once and allow what it asks for: notifications, Nearby devices (to see the camera's
   Wi-Fi come on), running in the background and, on some phones, auto-start.
2. On the camera, start Wi-Fi (**Connection to Smartphone**). The camera shows a network name and
   password.
3. In OM Time, tap **Find camera**, pick the camera's network and type the password. The app connects
   and sets the clock straight away. If the phone asks whether to connect to the device, allow it.
4. Done. From now on, whenever the camera's Wi-Fi is on, the clock is set automatically.

## How it works

**Setting the clock.** The camera runs a small web server at `192.168.0.10` on its own Wi-Fi network,
the one OI.Share uses. OM Time:

1. checks `get_caminfo.cgi` to confirm that an Olympus camera answers, and reads its model;
2. switches the camera to play mode with `switch_cammode.cgi?mode=play`;
3. sends `set_utctimediff.cgi?utctime=YYYYMMDDTHHMMSS&diff=+HHMM` with the UTC time and the phone's
   current offset (daylight saving included).

**Finding the camera without location permission.** The app recognises the camera's network by its
`192.168.0.x` address and the camera's answer. Find camera uses Android's companion-device picker,
which needs no location permission. On Android 13 and newer, the app reads the phone's Wi-Fi scan list
through the *Nearby devices* permission, declared "never for location", to see when the camera's
Wi-Fi comes on and goes off.

**Connecting to the camera's Wi-Fi.**

| Android | How the phone joins the camera |
|---|---|
| 13 and newer | When the camera's Wi-Fi appears in the phone's Wi-Fi scans, the app makes a temporary connection to it, syncs, and releases it, so the phone goes straight back to its usual Wi-Fi. This happens each time the camera's Wi-Fi is switched on. |
| 10 to 12 | The same temporary connection, tried when you unlock the phone and every few minutes while the screen is on. |
| 6 to 9 | The camera is saved as a Wi-Fi network, so the phone joins it when no other saved Wi-Fi is in range. **Sync now** switches to the camera from any network. |

After a sync the app leaves the camera alone for 3 minutes. The PEN E-PL7 switches its Wi-Fi off about a
minute after the phone leaves, so after that pause the camera's Wi-Fi being there means it was switched on
again. A camera that seems to keep its Wi-Fi on is left alone for 30 minutes, so the phone is not pulled
onto it every few minutes.

On Android 10 and newer, Android asks you once to approve the connection to the camera. After that it
connects without asking. The app does not register the camera as a Wi-Fi suggestion: that made Android
stay on the camera, which has no internet, as the phone's main Wi-Fi.

**Staying alive.** A small foreground service starts at boot. A system network callback and a periodic
job restart it if the phone stops it.

## Permissions

| Permission | Why |
|---|---|
| Internet, network state | Talk to the camera on its own Wi-Fi |
| Wi-Fi state / change Wi-Fi | Join the camera's Wi-Fi |
| Nearby devices (Android 13+) | See the camera's Wi-Fi in the phone's Wi-Fi scans. Declared "never for location" |
| Run at startup | Start watching for the camera after a restart |
| Foreground service | Keep watching in the background |
| Notifications | Show sync results |
| Ignore battery optimisation | Stop the phone from closing the app to save battery |
| Companion device | Allow Find camera, and let Android keep the app running for a paired camera |

The app has no internet access other than to the camera, and contains no analytics or ads. Camera Wi-Fi
passwords are stored only on the phone, in the app's private storage.

## Building

Requirements: JDK 17 and the Android SDK (platform 34, build-tools 34).

```bash
./gradlew testReleaseUnitTest lintRelease assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Release builds are signed with
the local debug key so that they install over each other when sideloaded. Use your own key if you
distribute builds.

The unit tests check the camera protocol against a simulated camera on localhost, and also cover the
camera list and the Find camera network-name filter.

## Status and known limits

- This is a new project. Tested so far: a PEN E-PL7 with a REDMAGIC 10 Pro on Android 16, where the
  automatic join and the clock command work. Reports from other cameras and phones are welcome.
  Please open an issue with your camera model, phone model and Android version.
- Debugging: some phones hide app logs. `adb shell setprop log.tag.OMTime V` turns on OM Time's log
  until the phone restarts, and `adb logcat -s OMTime` shows it.
- Find camera recognises networks named like `E-PL8-P-00000001` (model, letter, serial), the pattern
  shown in Olympus manuals. If your camera does not appear, use **Type in**, and please report its
  network-name format.
- Background switching from another Wi-Fi network needs Android 10 or newer. On some phones, Android uses
  the phone's main Wi-Fi for the camera connection, so the usual Wi-Fi drops for the few seconds a sync
  takes.
- The auto-start settings page is opened for the phone makers the app knows about. On other phones,
  the app opens its own settings page instead.

## Credits

- Made by **Peekungdev | peekung-development**.
- Camera protocol details from [olympus-wifi](https://github.com/joergmlpts/olympus-wifi) and the
  [OM-D Wi-Fi notes](https://0l.de/projects/omd/).

Olympus, OM System, OM-D, PEN and OI.Share are trademarks of their respective owners. This project is
not affiliated with or endorsed by OM Digital Solutions or Olympus.

---

## ภาษาไทย

**OM Time** คือแอป Android สำหรับตั้งนาฬิกากล้อง Olympus และ OM System ผ่าน Wi-Fi โดยอัตโนมัติ
เมื่อเปิด Wi-Fi ที่กล้อง โทรศัพท์จะเชื่อมต่อ ตั้งนาฬิกาตามเวลาและเขตเวลาของโทรศัพท์ แล้วกลับไปใช้ Wi-Fi เดิม
แอปเริ่มทำงานเองพร้อมโทรศัพท์ ไม่ต้องเปิดแอปทุกครั้ง

- รองรับ Android 6.0 ขึ้นไป ทั้ง 32 และ 64 บิต
- ไม่ต้องใช้สิทธิ์ตำแหน่ง ไม่ใช้อินเทอร์เน็ต และไม่มีการติดตามผู้ใช้
- รองรับภาษาไทยและภาษาอังกฤษ

**การตั้งค่าครั้งแรก:** เปิดแอปแล้วอนุญาตสิ่งที่แอปขอ เปิด Wi-Fi ที่กล้อง (เชื่อมต่อกับสมาร์ทโฟน)
แตะ **ค้นหากล้อง** เลือกเครือข่ายของกล้อง แล้วพิมพ์รหัสผ่านที่แสดงบนกล้อง เสร็จแล้ว

พัฒนาโดย **Peekungdev | peekung-development**
