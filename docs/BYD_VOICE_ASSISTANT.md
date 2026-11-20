# BYD voice assistant: finding write values and testing voice actions

This page explains how SharkOS CE used BYD's own voice assistant to learn the exact calls (and values) the car expects, and how you can do the same and use it as a test step. It was worked out on a Shark 6 (firmware `SOC_260811_S`, Android 11) on 21 Nov 2026.

> **Unofficial and read-only.** This is community research. It is not affiliated with or endorsed by BYD. Everything here reads BYD's installed app on your own car for interoperability. Do not redistribute BYD's files. Changing car settings is at your own risk: read [DISCLAIMER.md](../DISCLAIMER.md) and change one thing at a time while parked.

Status words used below: **VERIFIED** = tried on the car and checked against BYD's own screen or a read-back. **DECODED** = read from BYD's code only, not tried on the car.

## The short version

- The assistant is the app `com.byd.vrassistant` (BydVoice, version `S.10.1.7` on this firmware, in `/product/app/BydVoice/`). Its engine is Cerence ARK.
- When you ask it to do something, it makes the same `android.hardware.bydauto.*` calls SharkOS CE makes. So its code is a reliable list of **what the car expects**, including the write values.
- You cannot send it a spoken command from adb (see [What does not work](#what-does-not-work)). The working method is: **read its code to learn the call, fire that call yourself, and check the result on BYD's own screen.**

## Use it: find the write value for a spoken action

You need the car on adb over Wi-Fi, Android build-tools (for `dexdump`) and a text editor. No root.

1. **Pull the app** (read-only). In Git Bash set `MSYS_NO_PATHCONV=1` first, or the path is rewritten.
   ```
   adb -s <car-ip>:5555 pull /product/app/BydVoice/BydVoice.apk
   ```
2. **Disassemble** each `classes*.dex` inside it (unzip the APK first). The output is large (over a million lines for one file).
   ```
   dexdump -d classes2.dex > c2.txt
   ```
3. **Find the helper for the action.** The useful classes are in `com.byd.vrassistant.conversation`:
   - `BydSdkHelper`: window state, window lock, child lock, mobile data
   - `VehicleBasicHelper`: windows, locks, tray (the "trunk")
   - `HvacSetStateHelper`, `HvacHelper`, `Hvac*Helper`: climate, seat heat and ventilation, defrost
   - `VehicleScreenHelper`: screen brightness; `VehicleLampHelper`: interior light; `VehicleSettingsHelper`: Bluetooth, hotspot
4. **Read the method.** Search the dump for `] com.byd.vrassistant.conversation.<Class>.<method>:` and read down to the next method header. The lines that matter:
   - `const/4 vN, #int X`: a literal value
   - `aput vV, vA, vI`: puts a value into an `int[]` (this is how the window array is built)
   - `invoke-virtual {...}, Landroid/hardware/bydauto/<device>/<Class>;.<method>:(sig)`: the car call
   Walk back from the call to see which value each branch loads.
5. **Names of requests** inside the engine are the field names of `cerence/ark/assistant/controller/domain/<domain>/<Domain>RequestsEnum` (domains: vehiclecontrol, hvac, media, radio, phone, audiocontrol, system, hmi, weather, karaoke, ude).

Two traps found this way: a **read can be the inverse of its write** (the rear window lock reads 1 after you write 2), and the **first argument can differ** from BYD's own app (recirculate uses `1` in the assistant, `0` in BYD's climate app). Always confirm on the car.

## Use it: fire a decoded call safely

SharkOS CE accepts an `int[]` argument in `shark6_codes.json` as a nested array:

```json
"args": [[1, 0, 0, 0]]      // setWindowControlState(new int[]{1,0,0,0})
```

Try a new call without rebuilding by using an override file:

1. Copy `shark6_codes.json`, add a signal with `labels` and `writeLabels` (see `window.ctl.frontLeft` in the shipped file for a complete example) and save it as the override.
2. `adb push` it to `/sdcard/shark6_codes.json`, then on the car copy it into place (pushing straight into the app's folder is refused):
   ```
   cp /sdcard/shark6_codes.json /storage/emulated/0/Android/data/com.sharkhome.launcher/files/shark6_codes.json
   chmod 644 /storage/emulated/0/Android/data/com.sharkhome.launcher/files/shark6_codes.json
   ```
3. Use the built-in test hook (results are logged under the tag `SharkCar`; this head unit hides lower log levels):
   ```
   adb shell am broadcast -a com.sharkhome.launcher.ADB_TEST -n com.sharkhome.launcher/.car.AdbTestReceiver --es cmd reload
   ... --es cmd read   --es keys "your.signal"
   ... --es cmd verify --es keys "your.signal" --es target on --ei waitMs 8000
   ```
   `verify` reads the value, writes the target, reads it back, then writes the original value back.
4. Delete the override file and send `cmd=reload` again when you are done (the log then says `origin=built-in`).

Windows are slow (about 5 seconds to open), so use a long `waitMs` or poll the read yourself; the generic confirmation reports "not confirmed" for slow moves even when they worked. Keep people and objects clear of anything that moves.

## Use it as a test step when an action is exposed to voice

"The read-back matched our own table" is **not** proof. The recirculate tile once shipped inverted even though every read-back said `confirmed=true`. For each action:

1. Find the assistant's call for the phrase and write down the device, method and argument values.
2. Find the read it uses for the same state and what each value means.
3. Record the starting state so you can put it back.
4. Fire **your** write and read it back (allow for slow reports: 3 to 5 seconds, windows about 8).
5. Compare with the **outside truth**, not your own labels: BYD's own screen (zoom in on the icon) or the physical result. Example: BYD's climate screen shows a loop arrow inside the car for recirculate and an arrow entering from outside for fresh air.
6. Check the write and read directions agree with the assistant's code (is 1 on or off? is the read the inverse?).
7. Put the car back and read it again to prove it.
8. If you can test by voice (you speak, or the overlay shows the recognised text), say the phrase and check the car lands in the same state.
9. Record the verified encoding in `shark6_codes.json` (`_note`) and `BYD_SHARK6_REFERENCE.md`. Mark anything not tried as unverified.

## Calls found

| Action | Call | Values | Status |
| --- | --- | --- | --- |
| Open / close a window | `bodywork.BYDAutoBodyworkDevice.setWindowControlState(int[4])` | Index 0 = front left, 1 = front right, 2 = rear left, 3 = rear right; value 0 = leave alone, 1 = open, 2 = close, 4 = half, 5 = small gap | **VERIFIED** on the front-left window: `[1,0,0,0]` opened it, `[2,0,0,0]` closed it. Others and codes 4 and 5: DECODED |
| Read a window position | caradapter `body.getWindowOpenPercentLeftFront` (and the other three) | 0 to 100 % (97 to 98 when fully open) | **VERIFIED** |
| Recirculate | `ac.BYDAutoAcDevice.setAcCycleMode(x, mode)` | `mode` 1 = recirculate, 0 = fresh air (`x` is 0 in BYD's climate app, 1 in the assistant); read `getAcCycleMode` 2 = recirculate, 1 = fresh | **VERIFIED** with `x = 0` against BYD's screen |
| Rear window lock | `doorlock.BYDAutoDoorLockDevice.setRearWindowLock(v)`, read `getDoorLockStatus(6)` | Write 2 reads back 1 (locked); write 1 reads back 2 | Relationship **VERIFIED**; which means "locked" is from BYD's code |
| Bluetooth | `android.bluetooth.BluetoothAdapter.enable()` / `disable()` | Plain Android | **VERIFIED** |
| Screen brightness | `Settings.System` `screen_brightness` (0 to 255) | Written with `settings put system screen_brightness N` through the unit's own adb | Setting **VERIFIED** to change; visible dimming not yet confirmed |
| Rear tray (tailgate on the ute) | `setting.BYDAutoSettingDevice.voiceCtlBackDoor(int)`; read `bodywork.BYDAutoBodyworkDevice.getBackDoorCurState()` | 1 = open, 3 = close | DECODED only. It moves a heavy panel: only try it with the area clear |
| Rotate screen | `setting.BYDAutoSettingDevice.setScreenRotation(int)` | 1 = landscape, 2 = portrait | **VERIFIED** |
| Mobile data | `TelephonyManager.setDataEnabled(boolean)` by reflection | | Not usable: needs a privileged permission, and `svc data` / `cmd phone data` change nothing on this unit (the connection is the T-Box network) |
| Climate modes, rear A/C, defrost, seat heat and vent, steering wheel heat, air purifier, HUD, door lock, fragrance, reading light, drive mode | `ac.BYDAutoAcDevice`, `setting.BYDAutoSettingDevice`, `pm2p5.BYDAutoPM2p5Device`, `doorlock.BYDAutoDoorLockDevice`, `fragrance.BYDAutoFragranceDevice`, `energy.BYDAutoEnergyDevice` | Call names only | DECODED call names; values not decoded |

The Shark 6 is a ute: it has no sunroof, no boot and no adjustable rear windows, even though BYD's shared voice software lists those requests. On this car the "trunk" requests act on the rear tray.

## Talking to the assistant from adb

**Works**
- Start it listening without the wake word:
  ```
  adb shell am broadcast --receiver-foreground -a com.intent.action.StartVoice_From_Launcher -n com.byd.vrassistant/.receiver.HardKeyReceiver
  ```
  (Without `-n` and `--receiver-foreground`, Android blocks it as a background execution.)
- Its overlay bar shows what it thinks it heard, so a screenshot of the screen shows the transcript.
- It logs at warning level under tags `NEU_*` (for example `NEU_VoiceAssistantManager`). Its info and debug lines, including recognised text, are hidden on this head unit.
- SharkOS CE's ADB test hook has `--es cmd play --es file name.wav` to play a sound file from the app's files folder.

### What does not work
- **Playing speech from the car's own speakers.** The assistant did not hear it: its overlay only cycled its hints and nothing changed. Probably echo cancellation; not proven.
- **A text-input route.** The assistant app has none, and the Cerence ARK events (push-to-talk, cancel dialog and so on) carry no text.
- **BYD's own test receiver** (`AutoRunReceiver`, actions `bydvoice.broadcast.autobatch.start`, `.pcm`, `.stop`) accepts broadcasts from adb but replays prepared recordings from a data folder and a JSON dialog list, and restarts the voice engine on each call. The format was not worked out. After a probe the assistant recovered in about 5 seconds.

## What the engine understands

Language understanding runs in the engine and in BYD's cloud, so wording is free-form. The data pack inside the app (`assets/data_vr-BASE-8155.7z`, openable with 7-Zip) lists 2,186 intents in 39 domains; about 900 are in-car "settings and control" intents (named `settings_and_control:<action>:<object>`, for example open or close for windows, blinds, bass, brightness).

BYD's own English help shows examples such as "Open driver window", "Close all windows", "Turn on the AC", "Set the fan speed to level 3", "Switch on driver seat heating", "Turn on Bluetooth", "Make the screen brighter" and "Rotate screen".

To list everything on your own car (we do not republish BYD's lists):
- Examples by area: `assets/language/en.properties` inside `BydVoice.apk`.
- All intents by domain: `data_vr-BASE/asr_common/config/intent_mapping_domain_config.json` inside the `.7z` above (`domain_mapping`).
- Request names per domain: the `*RequestsEnum` classes in the dex dump (step 5 above). The car-body set (`vehiclecontrol`) has 75, climate has 19.

## Open items
- Front right window, and the half and small-gap window codes, are untried.
- Decode the remaining handlers: interior light, drive and road mode, camera, ADAS, child lock, engine sound, mirror heating, hotspot, Wi-Fi.
- Rear tray open and close, and how BYD switches the rear power points (they are not in the voice assistant).
- A real spoken test (you speak, photograph the overlay transcript) for each action.
- Mobile data: find BYD's own switch.
