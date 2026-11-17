# OM Time

**Automatic clock sync for Olympus and OM System Wi-Fi cameras, from your Android phone.**

Camera clocks drift, and they don't change time zone when you travel. OM Time fixes that without
any effort: when the camera's Wi-Fi is switched on, the phone connects to it, sets the camera clock
from the phone's time and time zone, and goes back to its usual Wi-Fi. It runs from the moment the phone
starts, so there is nothing to open.

Made by **Peekungdev | peekung-development**

---

## Features

- **Automatic sync**: sets the camera clock whenever the phone connects to the camera's Wi-Fi, with
  time zone and daylight saving taken from the phone.
- **Auto-connect**: joins the camera's Wi-Fi by itself, even from another Wi-Fi network, then returns
  the phone to that network.
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

1. Open OM Time once and allow what it asks for: notifications, running in the background and, on
   some phones, auto-start.
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
`192.168.0.x` address and the camera's answer, so it never needs to read Wi-Fi names. Find camera uses
Android's companion-device picker, which also needs no location permission.

**Connecting to the camera's Wi-Fi.**

| Android | How the phone joins the camera |
|---|---|
| 10 and newer | The camera is registered as a Wi-Fi suggestion, so the phone joins it when no other Wi-Fi is in range. While the screen is on, the app also checks for the camera when you unlock the phone and every 3 minutes. If the camera is there, the app makes a temporary connection to it, syncs, and releases it. Android remembers your approval, so this happens without prompts. |
| 6 to 9 | The camera is saved as a Wi-Fi network, so the phone joins it when no other saved Wi-Fi is in range. **Sync now** switches to the camera from any network. |

**Staying alive.** A small foreground service starts at boot. A system network callback and a periodic
job restart it if the phone stops it.

## Permissions

| Permission | Why |
|---|---|
| Internet, network state | Talk to the camera on its own Wi-Fi |
| Wi-Fi state / change Wi-Fi | Join the camera's Wi-Fi |
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

- This is a new project. The protocol and the app logic are tested against a simulated camera. Reports
  from real cameras and phones are welcome. Please open an issue with your camera model, phone model
  and Android version.
- Find camera recognises networks named like `E-PL8-P-00000001` (model, letter, serial), the pattern
  shown in Olympus manuals. If your camera does not appear, use **Type in**, and please report its
  network-name format.
- Background switching from another Wi-Fi network needs Android 10 or newer.
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
