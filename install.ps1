# OM Time - made by Peekungdev | peekung-development
#
# Installs OM Time on a phone connected with USB debugging, and grants over ADB what the app would otherwise ask
# for on first launch: notifications, running in the background, and (best effort) auto-start. The camera itself is
# added in the app (Find camera), because that needs the camera's Wi-Fi switched on.
# Usage: .\install.ps1            one phone connected
#        .\install.ps1 -Serial X  several devices; X from "adb devices"
param([string]$Serial)

$pkg = 'dev.olytimesync'
$apk = Get-ChildItem (Join-Path $PSScriptRoot 'OMTime-*.apk') | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $apk) { throw "No OMTime-*.apk next to this script." }

function Adb { if ($Serial) { & adb -s $Serial @args } else { & adb @args } }

Write-Host "Installing $($apk.Name)"
Adb install -r $apk.FullName
if ($LASTEXITCODE -ne 0) { throw "adb install failed." }

$sdk = [int]((Adb shell getprop ro.build.version.sdk) -join '').Trim()
$maker = ((Adb shell getprop ro.product.manufacturer) -join '').Trim()
Write-Host "Phone: $maker, Android API $sdk"

if ($sdk -ge 33) { Adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS }

# Battery optimisation exemption (the same list as Settings > Battery optimisation; survives reboots).
Adb shell dumpsys deviceidle whitelist "+$pkg"
Adb shell appops set $pkg RUN_IN_BACKGROUND allow
if ($sdk -ge 28) { Adb shell appops set $pkg RUN_ANY_IN_BACKGROUND allow }

if ($maker -match 'xiaomi|redmi|poco') {
    # MIUI / HyperOS keeps its auto-start switch in app-op 10008. Not documented; if it does not take, the app
    # opens the auto-start page itself on first launch.
    Adb shell appops set $pkg 10008 allow
}

# Launching once takes the app out of the "stopped" state Android puts new installs in; until then it gets no
# boot broadcast.
Adb shell am start -n "$pkg/.MainActivity"
Write-Host "Done. Check the app's 'Run in background' and 'Start with the phone' sections."
