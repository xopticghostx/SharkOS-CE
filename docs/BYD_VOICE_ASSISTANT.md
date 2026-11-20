# Use BYD's voice assistant to find car write values

BYD's voice assistant makes the same car calls SharkOS CE makes. Its app therefore shows you the exact call and values the car expects for things like "open the window". This guide shows how to read them and check them. Everything else (every command, the full list of calls, what does not work) is in [BYD_VOICE_ASSISTANT_FULL.txt](BYD_VOICE_ASSISTANT_FULL.txt).

> **Unofficial and read-only.** Community research, not affiliated with or endorsed by BYD. You only read the app that is already on your car. Changing car settings is at your own risk: read [DISCLAIMER.md](../DISCLAIMER.md), change one thing at a time, and do it while parked.

Tested on a Shark 6, firmware `SOC_260811_S`.

## Find a write value in four steps

You need the car on adb over Wi-Fi and Android build-tools (for `dexdump`). No root.

**1. Pull the app.** In Git Bash, set `MSYS_NO_PATHCONV=1` first.
```
adb -s <car-ip>:5555 pull /product/app/BydVoice/BydVoice.apk
```

**2. Read the call.** Unzip the APK and run `dexdump -d classes2.dex > c2.txt` (and the other `classes*.dex`). Search for the action in the helper classes under `com.byd.vrassistant.conversation`:

| Action | Look in |
| --- | --- |
| Windows, locks | `BydSdkHelper`, `VehicleBasicHelper` |
| Climate, seat heat | `HvacSetStateHelper`, `HvacHelper` |
| Screen brightness | `VehicleScreenHelper` |
| Bluetooth, hotspot | `VehicleSettingsHelper` |

Read down to the lines `invoke-virtual ... Landroid/hardware/bydauto/...` (the car call) and `const/4 vN, #int X` or `aput` (the values).

**3. Try it.** Add the call as a signal in an override copy of `shark6_codes.json` (see `window.ctl.frontLeft` in the shipped file). An `int[]` argument is a nested array: `"args": [[1, 0, 0, 0]]`. Put it on the car and test:
```
adb push shark6_codes.json /sdcard/shark6_codes.json
adb shell cp /sdcard/shark6_codes.json /storage/emulated/0/Android/data/com.sharkhome.launcher/files/shark6_codes.json
adb shell chmod 644 /storage/emulated/0/Android/data/com.sharkhome.launcher/files/shark6_codes.json
adb shell am broadcast -a com.sharkhome.launcher.ADB_TEST -n com.sharkhome.launcher/.car.AdbTestReceiver --es cmd reload
adb shell am broadcast -a com.sharkhome.launcher.ADB_TEST -n com.sharkhome.launcher/.car.AdbTestReceiver --es cmd verify --es keys "your.signal" --es target on --ei waitMs 8000
```
`verify` writes your value, reads it back, then puts the original value back. Results are in the log under the tag `SharkCar`. Delete the override file and send `cmd reload` again when you are done.

**4. Check it on BYD's own screen.** Not just the read-back: the read-back only proves the car matches *your* table. Look at the real result (for example BYD's climate screen, or the window actually moving).

## Quick check before you trust a value

- Did you note the starting state, and put it back afterwards?
- Does BYD's screen (or the physical result) agree with what you wrote?
- Is the read the **inverse** of the write? (The rear window lock reads 1 after you write 2.)
- Does the first argument match what BYD's own app sends? (Recirculate uses `1` in the assistant and `0` in BYD's climate app.)
- Slow things (windows take about 5 seconds) need a long `waitMs`; "not confirmed" can still mean it worked.

## Verified so far

| Action | Call | Values |
| --- | --- | --- |
| Open / close a window | `bodywork.BYDAutoBodyworkDevice.setWindowControlState(int[4])` | Array index 0 front left, 1 front right, 2 rear left, 3 rear right; 1 = open, 2 = close, 0 = leave alone (front left verified) |
| Recirculate | `ac.BYDAutoAcDevice.setAcCycleMode(0, mode)` | 1 = recirculate, 0 = fresh air; read 2 = recirculate, 1 = fresh |
| Bluetooth | `BluetoothAdapter.enable()` / `disable()` | Plain Android |
| Screen brightness | `settings put system screen_brightness N` | 0 to 255 |

More calls (rear tray, climate, seats, HUD and others) are decoded but not tried; they are listed with their status in the [full text](BYD_VOICE_ASSISTANT_FULL.txt).

## Good to know

- You cannot send the assistant a spoken or typed command from adb, so the method is: **read its code, fire the call yourself, check BYD's screen.** The full text explains what was tried.
- To see what the assistant thinks it heard, start it listening and take a screenshot (the overlay shows the text):
  ```
  adb shell am broadcast --receiver-foreground -a com.intent.action.StartVoice_From_Launcher -n com.byd.vrassistant/.receiver.HardKeyReceiver
  ```
- The Shark 6 is a ute: no sunroof, boot or adjustable rear windows, even though BYD's shared software lists them.
