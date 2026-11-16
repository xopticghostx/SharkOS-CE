# SharkOS CE Windows installer

Installs SharkOS CE on a BYD Shark 6 from a Windows PC, over Wi-Fi.

## Use it

1. Put the launcher APK (and the SharkOS-CE helper APK) in this folder.
2. Park the car, switch it on, and connect it to the same Wi-Fi as the PC.
3. Double-click **Install SharkOS CE.bat** and follow the prompts.

The installer:

- finds `adb`, or downloads Google's platform-tools if the PC doesn't have it
- scans the PC's local networks for a device with ADB on (port 5555) and checks it is a BYD head unit
- if nothing is found, shows how to switch on ADB in the car and how to find the car's IP address
- tells you when a device answers but ADB is off, and when the car is waiting for you to tap Allow
- installs the launcher, grants its permissions, installs the SharkOS-CE helper and opens the app

No admin rights are needed. Nothing is installed on the PC apart from `%LOCALAPPDATA%\SharkOS-CE` (ADB download, log, last car IP).

## Options

```
powershell -ExecutionPolicy Bypass -File Install-SharkOS-CE.ps1 -CarIp 192.168.1.123
```

| Option | Use |
| --- | --- |
| `-CarIp` | Skip the scan and use this address |
| `-ApkPath`, `-WakeHelperApk` | Use these APK files |
| `-SkipWakeHelper` | Install the launcher only |
| `-AdbPath` | Use this `adb.exe` |
| `-Unattended` | Ask no questions and take the defaults |

APK file names it recognises: `*sharkos*.apk`, `*shark*launcher*.apk`, `app-release*.apk`, `app-debug*.apk`, and `*helper*.apk` for the helper. If none are in the folder it tries the project's build output, then the latest GitHub release.

The steps for switching on ADB follow the community guide at https://github.com/MorghusDragon/BYD-Shark-Sideloading.
