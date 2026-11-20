# BYD Shark 6 (Di5.0_DXF_W / SOC_260811_S) — Car API & Switch Reference

This reference documents the exact services, device classes, permissions, method signatures, and raw value encodings discovered from the BYD Shark 6 head unit (`autoType = 143`, `platformCode = Di5.0_DXF_W`, Android 11, firmware `SOC_260811_S`) via live probing (`car-exports/shark-explorer-20261105-100400.txt`) and disassembling factory APKs (`/system/app/BydCarSettings`, `/system/app/BydHvac`, `/system/app/CarAdapterService`).

---

## 1. Architecture & Backends on the Head Unit

There are three ways BYD's own apps talk to the vehicle on this firmware:

### A. `CarAdapterService` (`com.ts.appservice.caradapter`)
- Bound via action `com.ts.appservice.caradapter.CarAdapterService` (`ICarAdapterService`).
- Calls `getCarAdapter(String name)` on the root binder.
- **Working adapters on Shark 6** (must all be included in `CarAdapterBackend.ADAPTERS`):
  - `"hvac"` → `com.ts.lib.caradapter.hvac.IHvacAdapterService`
  - `"body"` → `com.ts.lib.caradapter.body.ICarBodyService`
  - `"setting"` → `com.ts.lib.caradapter.setting.ISettingService`
  - `"cabin"` → `com.ts.lib.caradapter.cabin.ICabinService`
  - `"assist"` → `com.ts.lib.caradapter.assist.ICarAssistAdapterService` *(critical for ADAS / DiPilot / DMS / CPD)*
  - `"general"` → `com.ts.lib.caradapter.general.ICarGeneralService` *(EV/fuel range, SOC, odometer, brightness, units)*
  - `"sensor"` → `com.ts.lib.caradapter.sensor.ICarSensorService` *(speed, radar distances, light intensity, slope)*
  - `"cloud"` → `com.ts.lib.caradapter.cloud.ICarCloudService`
  - *(Note: `"seat"` and `"power"` return `null` on this firmware; seat methods live on `setting` and `cabin`, power methods live on `general`, `body`, and `bydauto.energy`.)*

### B. `android.hardware.bydauto.*` Device Singletons
- Loaded from the system classloader (or `com.byd.hvac` / `com.byd.carsettings` classloader) via `Class.forName("android.hardware.bydauto.<pkg>.<Class>").getMethod("getInstance", Context::class.java)`.
- Internally on DiLink 5.0 (`SOC_260811_S`), many `BYDAuto*Device` classes delegate to `com.ts.lib.caradapter.*` (`CarAssistAdapterManager`, `CarBodyManager`, `HvacAdapterManager`) or `com.ts.lib.settings.*` (`SettingsAdapterManager` bound to `TS.SETTINGS.SERVICE` / `com.ts.appservice.settings`) or `com.ts.lib.cluster.ClusterCommManager`.
- **Important Devices**:
  - `ac.BYDAutoAcDevice` — Climate & A/C
  - `dipilot.BYDAutoDiPilotDevice` — ADAS, Lane Assist, Speed Limit (ISLI/SLIF), DMS, Child Presence Detection (CPD)
  - `dms.BYDAutoDmsDevice` — Driver Monitoring (`DmsSettingsManager` wrapper)
  - `instrument.BYDAutoInstrumentDevice` — Instrument cluster, Full-screen Dash Map (`setInsNaviDisplayMode`), Day/Night mode
  - `audio.BYDAutoAudioDevice` — Hardware amplifier volume (`0..39`), mute, EQ, Dirac/sound stage
  - `setting.BYDAutoSettingDevice` — Vehicle convenience settings, HUD, locks, windows, mirrors, seats, regen
  - `energy.BYDAutoEnergyDevice` — EV/HEV mode (`getOperationMode`), terrain/road mode (`getRoadSurfaceMode`), e-Pedal, V2L/discharge, 50 km consumption
  - `bodywork.BYDAutoBodyworkDevice` — VIN, power level (ignition state), sunroof, windows, anti-theft
  - `tyre.BYDAutoTyreDevice` — Tyre pressure (`getTyrePressureValueByType(1..4)`)
  - `gearbox.BYDAutoGearboxDevice` — Gear position (`getGear`: `1=P, 2=R, 3=N, 4=D`)
  - `speed.BYDAutoSpeedDevice` — Speed, pedal depth (`getAccelerateDeepness`, `getBrakeDeepness`)
  - `engine.BYDAutoEngineDevice` — Engine RPM, power (kW), coolant/oil
  - `statistic.BYDAutoStatisticDevice` — Mileage, EV/HEV range, energy/fuel averages

### C. Required Manifest Permissions (`AndroidManifest.xml`)
Without both `_GET` and `_SET` permissions, `BYDAuto*Device` setters silently fail or throw `SecurityException`:
```xml
<uses-permission android:name="android.permission.BYDAUTO_AC_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_AC_GET" />
<uses-permission android:name="android.permission.BYDAUTO_AC_SET" />
<uses-permission android:name="android.permission.BYDAUTO_BODYWORK_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_BODYWORK_GET" />
<uses-permission android:name="android.permission.BYDAUTO_BODYWORK_SET" />
<uses-permission android:name="android.permission.BYDAUTO_SETTING_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_SETTING_GET" />
<uses-permission android:name="android.permission.BYDAUTO_SETTING_SET" />
<uses-permission android:name="android.permission.BYDAUTO_INSTRUMENT_GET" />
<uses-permission android:name="android.permission.BYDAUTO_INSTRUMENT_SET" />
<uses-permission android:name="android.permission.BYDAUTO_STATISTIC_GET" />
<uses-permission android:name="android.permission.BYDAUTO_SPEED_GET" />
<uses-permission android:name="android.permission.BYDAUTO_GEARBOX_GET" />
<uses-permission android:name="android.permission.BYDAUTO_TYRE_GET" />
<uses-permission android:name="android.permission.BYDAUTO_ENGINE_GET" />
<uses-permission android:name="android.permission.BYDAUTO_SENSOR_GET" />
<uses-permission android:name="android.permission.BYDAUTO_SENSOR_SET" />
<uses-permission android:name="android.permission.BYDAUTO_ADAS_GET" />
<uses-permission android:name="android.permission.BYDAUTO_ADAS_SET" />
<uses-permission android:name="android.permission.BYDAUTO_DIPILOT_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_DIPILOT_GET" />
<uses-permission android:name="android.permission.BYDAUTO_DIPILOT_SET" />
<uses-permission android:name="android.permission.BYDAUTO_DMS_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_DMS_GET" />
<uses-permission android:name="android.permission.BYDAUTO_DMS_SET" />
<uses-permission android:name="android.permission.BYDAUTO_POWER_GET" />
<uses-permission android:name="android.permission.BYDAUTO_POWER_SET" />
<uses-permission android:name="android.permission.BYDAUTO_ENERGY_COMMON" />
<uses-permission android:name="android.permission.BYDAUTO_ENERGY_GET" />
<uses-permission android:name="android.permission.BYDAUTO_ENERGY_SET" />
```

---

## 2. BYD Encoding Conventions (Gotchas)

1. **`1 = On, 2 = Off` vs `0 = Off, 1 = On`**:
   - Most CAN-backed BYD switches use **`1 = On, 2 = Off`** (where `0` means "invalid / not ready" on CAN).
   - However, some `BYDAuto*Device` getters/setters translate to `0 = Off, 1 = On`. Always check the live read value in `shark-explorer-20261105-100400.txt`.
2. **Asymmetric Read vs Write Codes**:
   - Some switches use different numbers for reading vs writing!
   - **Example (`advanced.laneAssist`)**:
     - `getLaneAssistType()` returns **`0` = Off**, `1` = Warning only, `2` = Departure prevention, **`3` = All (On)**.
     - `setLaneAssistType(int)` expects CAN command codes: **`1` = Off**, `2` = Warning only, **`4` = All (On)**, `5` = Departure prevention!
3. **Empty Stubs in `CarAdapterService` / `BYDAuto*Device`**:
   - Many methods exist on the AIDL interface or class but are no-op stubs that return `0` without changing the vehicle state (e.g. `caradapter.hvac.setAcCompressorState`, `bydauto.dipilot.BYDAutoDiPilotDevice.setISLIWarningLevelSwitchStatus`).
   - Always verify writes by reading back the signal after ~700 ms (`VehicleRepository.attempt()`).

---

## 3. Verified Switches & Signals (`shark6_codes.json`)

### A. Advanced Settings — All 5 Verified Working

| Feature | Signal Key | Read Method(s) & Codes | Write Method(s) & Codes | Notes |
| --- | --- | --- | --- | --- |
| **Lane Assist (LKS / LDW)** | `advanced.laneAssist` | `caradapter.assist.getLaneAssistType()`<br>`bydauto.dipilot.BYDAutoDiPilotDevice.getLaneAssistType()`<br>**Read**: `0` = Off, `1` = Warning, `2` = Prevention, `3` = All (On) | `bydauto.dipilot.BYDAutoDiPilotDevice.setLaneAssistType(int)`<br>**Write**: `1` = Off, `4` = On (All), `5` = Prevention, `2` = Warning | Disassembled from `BydCarSettings` `ActiveSafeViewModel`. Confirmed on vehicle. |
| **Traffic Sign Recognition (master)** *(verified 16 Nov)* | `advanced.trafficSign` | `caradapter.assist.getTrafficSignRecognize2B4State()`<br>**Read**: `1` = On, `0` = Off (dash value `getTrafficSignRecognitionValue` goes to 255 when off) | `caradapter.assist.setTrafficSignRecognizeState(int)`<br>**Write**: `2` = On, `1` = Off | `setSlifState` does nothing. Off greys out the three switches below in BYD's screen. Same 3-5 s delay. |
| **Intelligent Speed Limit Warning** *(verified 16 Nov)* | `advanced.speedLimitWarning` | `caradapter.assist.getIntelligentSpeedLimitAlert2B4State()`<br>**Read**: `1` = On, `0` = Off. (`getIsliWarningLevelSwitchStatus` moves the opposite way, `2` = On, `1` = Off, and its setter does nothing.) | `caradapter.assist.setIntelligentSpeedLimitAlertState(int)`<br>**Write**: `2` = On, `1` = Off | The car reports the new value 3-5 s after the write (BYD's own switch too), so confirm with a 7 s window. Older notes here had the ISLI value inverted: writing 2 for "off" switched the warning ON. |
| **Intelligent Speed Limit Control** *(verified 16 Nov)* | `advanced.speedLimitControl` | `caradapter.assist.getIntelligentSpeedLimitControl32FState()`<br>**Read**: `1` = On, `0` = Off | `caradapter.assist.setIntelligentSpeedLimitControlState(int)`<br>**Write**: `2` = On, `1` = Off | Same 3-5 s delay. |
| **Speed Limit Change Prompt Tone** *(verified 16 Nov)* | `advanced.speedLimitTone` | `caradapter.assist.getTsrLitSpdChgSwitchState()`<br>**Read**: `0` = Tone on, `1` = Tone off (inverted) | `caradapter.assist.setTsrLitSpdChgSwitchState(int)`<br>**Write**: `2` = On, `1` = Off | Same 3-5 s delay. All three sit under ADAS > Safety Assist > Traffic Sign Recognition. |
| **Driver Monitoring (DMS)** | `advanced.driverMonitoring` | `caradapter.assist.getDmsSwitchStatus()`<br>`bydauto.dipilot.BYDAutoDiPilotDevice.getDmsSwitchStatus()`<br>**Read**: `1` = On, `2` (or `0`) = Off | `bydauto.dipilot.BYDAutoDiPilotDevice.setDmsSwitchStatus(int)`<br>`caradapter.assist.setDmsSwitchStatus(int)`<br>**Write**: `1` = On, `2` = Off | Note: `BYDAutoDmsDevice.setDmsSwtichStatus` (with BYD's typo `Swtich`) is a `void` method on `DmsSettingsManager` that did not flip the CAN signal; `BYDAutoDiPilotDevice.setDmsSwitchStatus` works immediately. |
| **Child Presence Detection (CPD)** | `advanced.childPresence` | `caradapter.assist.getCpdStatus()`<br>`bydauto.dipilot.BYDAutoDiPilotDevice.getCpdStatus()`<br>**Read**: `1` (or `3`) = On, `2` (or `0`) = Off | `bydauto.dipilot.BYDAutoDiPilotDevice.setCpdStatus(int)`<br>`caradapter.assist.setCpdStatus(int)`<br>**Write**: `1` = On, `2` = Off | Confirmed on vehicle. Also see `caradapter.assist.getCpdDelayStatus()`. |
| **Full-screen Dash Map** | `advanced.dashMap` | `bydauto.instrument.BYDAutoInstrumentDevice.getInsNaviDisplayMode()`<br>**Read**: `1` (or `0`) = Simple, `2` = Full screen | `bydauto.instrument.BYDAutoInstrumentDevice.setInsNaviDisplayMode(int)`<br>**Write**: `1` = Simple, `2` = Full screen | Disassembled from `BydCarSettings` `InstrumentFragment` (`buttonIndex + 1`). Requires `BYDAUTO_INSTRUMENT_SET`. Sends command via `ClusterCommManager`. |

---

### B. Audio & Media

| Feature | How it Works on Shark 6 |
| --- | --- |
| **Hardware Amplifier Volume (`0..39`)** | Android's `AudioManager.getStreamVolume(STREAM_MUSIC)` is fixed/decoupled from the physical volume knob. Real car volume is on `android.hardware.bydauto.audio.BYDAutoAudioDevice`:<br>- Read: `getAudioVolume()` (returns `0..39`)<br>- Write: `setAudioVolume(int)` (`0..39`)<br>- Mute: `getAudioMuteState()` / `setAudioMuteState(int)`<br>- Fallback read: `Settings.System` key `"android.car.VOLUME_GROUP/1"` (music) and `"android.car.VOLUME_GROUP/0"` (navigation/prompt). |
| **Equaliser & Sound Stage** | Stored in `Settings.Global`:<br>- `android.car.EQUALIZER/PRESET` (seen: `2`)<br>- `android.car.EQUALIZER/BAND_0`, `BAND_1`, ... (seen: `900`)<br>- `android.car.AUDIO_CONTROL/dyna_sound_field_focus` (`0`)<br>- `android.car.AUDIO_CONTROL/ktv_mode` (`0`) |
| **Media Notification Listener** | BYD hides the standard Android `ACTION_NOTIFICATION_LISTENER_SETTINGS` activity (`ActivityNotFoundException`). Enable via:<br>1. `cmd notification allow_listener com.sharkhome.launcher/com.sharkhome.launcher.media.MediaSessionListener`<br>2. `Settings.Secure.putString(cr, "enabled_notification_listeners", ...)` |

---

### C. Climate & Seats (`caradapter.hvac`, `bydauto.ac.BYDAutoAcDevice`, `caradapter.setting`)

| Feature | Read Method & Encoding | Write Method & Encoding |
| --- | --- | --- |
| **Climate Power** | `bydauto.ac.BYDAutoAcDevice.getAcStartState()` (`0` = Off, `1` = On)<br>*(Note: `caradapter.hvac.getAcStartState()` returns `2` when Off)* | **On**: `bydauto.ac.BYDAutoAcDevice.start(0)` or `caradapter.hvac.start(0)`<br>**Off**: `bydauto.ac.BYDAutoAcDevice.stop(0)` or `caradapter.hvac.stop(0)` |
| **A/C Compressor** | `caradapter.hvac.getAcCompressorMode()` (`0` = Off, `1` = On)<br>*(Note: `getAcCompressorState` is an empty stub)* | **On**: `bydauto.ac.BYDAutoAcDevice.setAcCompressorMode(0, 1)` (after `start(0)`)<br>**Off**: `setAcCompressorMode(0, 2)` or `stop(0)` |
| **Auto Mode** *(verified 7 Nov)* | `caradapter.hvac.getAcControlMode()` (`2` = Auto, `1` = Manual)<br>*(`BYDAutoAcDevice.getAcControlMode()` translates to `0` = Auto, `1` = Manual)* | `caradapter.hvac.setAcControlMode(device, mode)` — **two args**, `2` = Auto, `1` = Manual. There is no one-arg setter. Changing airflow drops to Manual. |
| **Temperature (Driver / Passenger)** | `caradapter.hvac.getTempratureMain()` / `getTempratureDeputy()` (`17..32` integer °C)<br>`bydauto.ac.BYDAutoAcDevice.getTemprature(int area)` (`1` = Driver, `2` = Passenger) | `caradapter.hvac.setAcTemperatureMain(int)` / `setAcTemperatureDeputy(int)` (`17..32` integer °C)<br>*(Note: BYD Shark 6 HVAC uses 1°C integer steps `17..32`, NOT `0.5°C` floats)* |
| **Fan Speed (`0..7`)** | `caradapter.hvac.getAcWindLevel()` (`0..7`) | `caradapter.hvac.setAcWindLevel(int)` / `bydauto.ac.BYDAutoAcDevice.setAcWindLevel(0, int)` |
| **Airflow Mode** *(verified 7 Nov)* | `bydauto.ac.BYDAutoAcDevice.getAcWindMode()` (`1` = Face, `2` = Face+Feet, `3` = Feet, `4` = Feet+Windscreen, `6` = Face+Feet+Windscreen) | `bydauto.ac.BYDAutoAcDevice.setAcWindMode(0, int)` (passes straight through to `HvacAdapterManager.setAcWindMode(device, mode)`) |
| **Recirculate** | `caradapter.hvac.getAcCycleMode()` (`2` = Recirc, `1` = Fresh; corrected 21 Nov 2026, checked against BYD's climate screen icon) | `bydauto.ac.BYDAutoAcDevice.setAcCycleMode(0, mode)` (`1` = Recirc, `0` = Fresh; an earlier entry had these inverted). The caradapter setter is two-arg only and uses different codes. |
| **Max Cooling** | `caradapter.hvac.getAcMaxCoolingState()` (`1` = On, `2` = Off) | `caradapter.hvac.setAcMaxCoolingState(int)` (`1` = On, `2` = Off) |
| **Front Demist** | `caradapter.hvac.getAcDefrostStateFront()` (`1` = Off, `2` = On) | `caradapter.hvac.setAcDefrostStateFront(int)` (`1` = Off, `2` = On) |
| **Rear Demist** *(verified 7 Nov)* | `bydauto.ac.BYDAutoAcDevice.getAcDefrostState(2)` (`1` = On, `0` = Off). Raw `caradapter.hvac.getElectricDefrostState()` reads `0` when off. | `bydauto.ac.BYDAutoAcDevice.setAcDefrostState(device, 2, 1/0)` (area `1` = front, `2` = rear). The wrapper maps `1`→`2`, `0`→`1` and calls `caradapter.hvac.setElectricDefrostState(int)`, so the raw code is `2` = On, `1` = Off. |
| **Seat Heating (Driver / Passenger)** *(verified for driver 8 Nov)* | `caradapter.setting.getSeatHeatingStateDriver()` / passenger `getSeatHeatingNotL1PState()` (`1` = Off, `2` = Low, `3` = High). `getSeatHeatingStatePassenger()` reads `0`: not used. | `caradapter.setting.setSeatHeatingStateDriver(int)` / `setSeatHeatingStatePassenger(int)`: **reversed**, `3` = Off, `2` = Low, `1` = High. |
| **Seat Ventilation (Driver / Passenger)** *(verified for driver 8 Nov)* | `caradapter.setting.getSeatVentilatingState()` (driver) / `getSeatVentilatingNotL1PState()` (passenger) (`1` = Off, `2` = Low, `3` = High) | `caradapter.setting.setSeatVentilatingStateDriver(int)` / `setSeatVentilatingStatePassenger(int)`: **reversed**, `3` = Off, `2` = Low, `1` = High. |
| **Steering Wheel Heating** | `caradapter.setting.getSteeringWheelHeatingState()` / `caradapter.cabin.getSteeringWheelWarmState()` | `caradapter.setting.setSteeringWheelHeatingState(int)` / `caradapter.cabin.setSteeringWheelWarmState(int)` |

---

### D. Other Useful Switches Discovered in Disassembly & Probe Export (Available for Future Features)

#### 1. ADAS & Safety (`caradapter.assist` & `bydauto.dipilot.BYDAutoDiPilotDevice`)
- **Blind Spot Detection (BSD)**:
  - Read: `caradapter.assist.getBlindMonitorState()` (`1` = On, `2` = Off) / `getBsdState()`
  - Write: `caradapter.assist.setBlindMonitorState(int)` / `setBsdState(boolean)`
- **Door Open Warning (DOW)**:
  - Read: `caradapter.assist.getOpenDoorAlertState()` (`1` = On, `2` = Off) / `isOpenDoorWarning()`
  - Write: `caradapter.assist.setOpenDoorAlertState(int)` / `setOpenDoorWarning(boolean)`
- **Front / Rear Cross Traffic Alert & Brake (FCTA / FCTB / RCTA / RCTB)**:
  - Read: `getFrontTrafficCrossAlertState()` (`1`), `getFrontTrafficBrakeAlertState()` (`1`), `getRearTrafficCrossAlertState()` (`1`), `getRearTrafficBrakeAlertState()` (`1`)
  - Write: `setFrontTrafficCrossAlertState(int)`, `setFrontTrafficBrakeAlertState(int)`, `setRearTrafficCrossAlertState(int)`, `setRearTrafficBrakeAlertState(int)`
- **Rear Collision Warning (RCW)**:
  - Read: `getRearCrashAlertState()` (`1` = On)
  - Write: `setRearCrashAlertState(int)`
- **High Beam Assist (HMA / AI Distance Light)**:
  - Read: `caradapter.assist.getAiDistanceLight2B4State()` (`2`) / `bydauto.adas.BYDAutoADASDevice.getHMAState()`
  - Write: `caradapter.assist.setAiDistanceLightState(int)`
- **Head-Up Display (HUD)** (`caradapter.setting` / `bydauto.setting.BYDAutoSettingDevice`):
  - State: `getHudState()` (`1` = On), `setHudState(int)` / `caradapter.general.setHudOffState(int)`
  - Brightness: `getHudBrightness()` (seen: `11`)
  - Height: `getHudHeight()` (seen: `1`)
  - Rotation Angle: `getHudRotationAngle()` (seen: `6`)
  - Display Content: `getHudDisplayContentDriver()` (`1`), `getHudDisplayContentNavigation()` (`1`)

#### 2. Drive Modes, Regen, Terrain & Off-Road (`caradapter.setting`, `bydauto.energy.BYDAutoEnergyDevice`)
- **EV / HEV Operation Mode**:
  - Read: `bydauto.energy.BYDAutoEnergyDevice.getOperationMode()` (seen: `3`), `caradapter.body.getOperationMode()`
- **Road / Terrain Surface Mode**:
  - Read: `caradapter.setting.getGoalRoadMode()` (`1`), `bydauto.energy.BYDAutoEnergyDevice.getRoadSurfaceMode()` (`1`)
  - Write: `caradapter.setting.setGoalRoadMode(int)`
- **Hill Descent Control (HDC)** *(verified 7 Nov; BYD switch is ADAS › Safety Assist › Hill Descent Control)*:
  - Read: `caradapter.assist.getSteepDescentSlowDownState()` (`1` = On, `0` = Off). `caradapter.cabin.getHdcState()` stays `0` with the switch on (likely "HDC actively braking"), so don't use it for the switch.
  - Write: `caradapter.assist.setSteepDescentSlowDownState(int)`: `1` = On, `2` = Off (`0` is ignored). `bydauto.adas.BYDAutoADASDevice.setHDCState` is an empty stub; `BYDAutoDiPilotDevice.setSteepDescentSlowDownState` passes straight through.
- **Regenerative Braking (Energy Feedback)**:
  - Read: `caradapter.setting.getEnergyFeedback()` (seen: `2`; `1` = Standard, `2` = High)
  - Write: `caradapter.setting.setEnergyFeedback(int)`
- **Brake Pedal Feel Mode**:
  - Read: `caradapter.setting.getBrakePedalMode()` (seen: `1`; `1` = Comfort, `2` = Sport)
  - Write: `caradapter.setting.setBrakePedalMode(int)`
- **Steering Assist Feel Mode**:
  - Read: `caradapter.setting.getSteerAssis()` (seen: `1`; `1` = Comfort, `2` = Sport)
  - Write: `caradapter.setting.setSteerAssis(int)`
- **Comfortable Parking (CST)**:
  - Read: `caradapter.setting.getComfortableParkingState()` (`1` = On)
  - Write: `caradapter.setting.setComfortableParkingState(int)`
- **Sand Mode ABS / Wading Pattern**:
  - Read: `caradapter.setting.getSandAbsModeState()` (`1`), `getWadingPatternStateTips()` (`0`)
  - Write: `caradapter.setting.setSandAbsModeState(int)`
- **Trailer / Towing Mode**:
  - Read: `caradapter.setting.getTrailerModeState()` (`2` = Off), `getTrailerDragType()` (`15`), `getTrailerLightCheckState()` (`2`)
  - Write: `caradapter.setting.setTrailerModeState(int)`, `setTrailerDragType(int)`, `setTrailerLightCheckState(int)`

#### 3. Body, Windows, Mirrors, Locks & Lighting (`caradapter.body`, `caradapter.setting`, `caradapter.cabin`)
- **Screen Rotation**:
  - Write: `bydauto.setting.BYDAutoSettingDevice.setScreenRotation(int)`: `1` = landscape, `2` = portrait (verified 20 Nov 2026, same call as BYD's dock). `getScreenAngle` reads 0 in both orientations.
- **Exterior Mirrors Auto-Fold**:
  - Read: `caradapter.body.getAutoExternalRearMirrorFoldedState()` (`2`), `caradapter.setting.getAutoExternalRearMirrorFollowUpSwitch()` (`1`)
  - Write: `caradapter.cabin.setAutoExternalRearMirrorFoldState(int)`
- **Windows & Sunroof Positions**:
  - Read: `caradapter.cabin.getWindowRowOneLeftPos()`, `getWindowRowOneRightPos()`, `getWindowRowTwoLeftPos()`, `getWindowRowTwoRightPos()`, `getWindowRoofTopPos()`, `getWindowSunShadePos()`
  - Write: `caradapter.cabin.setWindowRowOneLeftPos(int)`, `setWindowRowOneRightPos(int)`, `setWindowRowTwoLeftPos(int)`, `setWindowRowTwoRightPos(int)`, `setSunRoofUpPos(int)`, `setSunShadeStatus(int)`
- **Cargo Bed Light & Welcome Lights**:
  - Cargo Light: `caradapter.body.getCargoLightSwitchState()` (`2` = Off)
  - Smart Welcome Light: `caradapter.setting.getSmartWelcomeLightState()` (`1` = On), `setSmartWelcomeLightState(int)`
- **Wireless Phone Charger**:
  - Read: `caradapter.body.getWirelessChargingSwitchState()` (`1` = On), `getWirelessChargingState()` (`3`), `getWirelessChargingPowerFRState()` (`15` W)
- **V2L / AC Charging & Discharging**:
  - Charging Power: `caradapter.body.getChargingPower()` (seen: `5.3` kW), `getChargingRestTime()` (`[hours, mins]`)
  - V2G / External Power: `caradapter.cabin.getExternalChargingPower()` (seen: `4260.1` W)
  - SOC Target: `caradapter.setting.getSOCTarget()` (seen: `25`%), `setSOCTarget(int)`
- **Brightness & Day/Night Mode**:
  - Day/Night Mode: `bydauto.instrument.BYDAutoInstrumentDevice.getDayNightMode()` (`1` = Day, `2` = Night)
  - Instrument Cluster Brightness: `bydauto.instrument.BYDAutoInstrumentDevice.getInstrumentBrightness()` (seen: `6`), `caradapter.cabin.setInstrumentBrightness(int)`
  - Center Screen Brightness: `Settings.System.SCREEN_BRIGHTNESS` (seen: `169`), `caradapter.general.setHomeScreenBrightness(int)`

#### 4. Android Auto & Apple CarPlay Media Bridge (`com.ts.car.core.ServiceProvider`)
On the BYD Shark 6 (DiLink 5.0), ThunderSoft's Android Auto (`com.ts.androidauto.projectionservice` / `com.ts.androidauto.app`) and Apple CarPlay (`com.ts.carplay.service` / `com.ts.carplay.app`) **do NOT register a standard Android `MediaSession`** with `MediaSessionManager`. Instead, BYD's stock dashboard media player widget (`com.byd.localmusic` / `BydLocalMedia.apk`, classes `com.byd.music.ThirdSDK.AndroidAuto.AndroidAutoManager` and `com.byd.music.ThirdSDK.Carplay.CarplayManager`) connects via ThunderSoft's platform service manager:
- **Entry Point**: `com.ts.car.core.ServiceProvider.get(Class<T> serviceInterface, Context context)` (loaded via `createPackageContext("com.byd.localmusic", Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY).classLoader`).
- **Required Manifest Permissions**:
  - `car.permission.ANDROIDAUTO`, `car.permission.ANDROIDAUTO_APP`, `car.permission.ANDROIDAUTO_PROJECT`
  - `car.permission.CARPLAY`, `car.permission.CARPLAY_APP`
- **Android Auto (`ts.car.service.androidauto.IAndroidAutoAppManager`)**:
  - Connection listener: `registerConnectionListener(ts.car.service.IServiceConnectionListener)` (`onServiceConnected`, `onServiceDisconnected`)
  - Session listener: `registerAndroidAutoSessionStatusListener(ts.car.service.androidauto.IAndroidAutoSessionStatusListener)` (`onSessionStatusChanged(int linkStatus, int errorCode, String msg)`, `linkStatus == 4` means connected)
  - Media listener: `registerMediaDataListener(ts.car.service.androidauto.IMediaDataListener)`:
    - `onMediaMetadataChanged(ts.car.service.androidauto.data.MediaMetadata)`: `getSong()`, `getArtist()`, `getAlbum()`, `getDurationSeconds()`, `getAlbumArt()` (`byte[]`), `getArtworkPath()`
    - `onMediaProgressChanged(int progressSeconds)`
    - `onMusicStatusChanged(int status)` (`1` = playing)
  - Polling / State getters: `getAndroidAutoSessionStatus()` (`4` = connected), `getMusicStatus()` (`1` = playing), `getMediaProgress()` (seconds), `getMediaMetadata()`
  - Transport controls: `play()`, `pause()`, `next()`, `previous()`
- **Apple CarPlay (`ts.car.service.carplay.ICarPlayAppManager`)**:
  - Connection listener: `registerConnectionListener(ts.car.service.IServiceConnectionListener)`
  - Session listener: `registerCarPlayStateListener(ts.car.service.carplay.ICarPlayStateListener)`. Real methods (dexdump of ts-platform-library.jar, 13 Nov): `onSessionStateChanged(int)`, `onSessionConnectStateChanged(int, boolean, String)`, `onCarPlaySessionConnectStateChanged(int, boolean, String, boolean)`, `onCarPlayMediaFocusStateChanged(boolean)`, `onAppStateChanged(int, boolean, int)`. There is NO `onSessionStatusChanged`. SessionStatus: 1 activating, 2 activated, 3 deactivating, 4 deactivated, 5 connect failed. `getCarPlaySessionStatus()` polls the same value.
  - Now Playing listener: `registerNowPlayingListener(ts.car.service.carplay.INowPlayingListener)`:
    - `onNowPlayingInfoChanged(ts.car.service.carplay.data.NowPlayingInfo)` / `onArtworkUpdate(NowPlayingInfo)`: `getMediaItemTitle()`, `getMediaItemArtist()`, `getAlbumTitle()`, `getMediaItemPlaybackDurationMs()`, `getPlaybackElapsedTimeMs()`, `getPlaybackStatus()` (`1` = playing), `getArtworkData()` (`byte[]`), `getArtworkPath()`
  - Transport controls: `sendRemoteMediaPlayback(int cmd, int keyState)` called in pairs (`keyState = 0` down, then `keyState = 1` up):
    - `cmd = 64` (`0x40`): Play / Pause
    - `cmd = 4` (`0x04`): Next track
    - `cmd = 8` (`0x08`): Previous track


---

### Power / standby (8 Nov, from BydCarPowerService)

- At every standby `CarPowerOnStandbyState.setOtherState` calls `CarPowerBinder.forceStopThirdApp()`: every non-system, non-stopped app is force-stopped except `FORCE_STOP_WHITELIST` = `tunein.player`, `com.byd.tunein`. Force-stopped apps get no broadcasts (incl. boot / screen-on) until started explicitly.

---

### Newly Verified Read/Write BYD Settings (20 Nov 2026, live read-back confirmed)

All 12 signals below were verified live on the car (`192.168.50.159:5555`, ACC off, parked) with `confirmed=true` in both directions and restored (proof: `.claude/proof/0.6.774/readback-byd-settings.txt`):
- **Energy / Mode Memory** (`bydauto.energy.BYDAutoEnergyDevice`, `1` = On, `2` = Off; `0` ignored):
  - `energy.saveMemory`: `getMemoryElectricState()` / `setMemoryElectricState(int)`
  - `energy.sportMemory`: `getMemorySportState()` / `setMemorySportState(int)`
  - *(Note: `caradapter.body.setIntelligentOrCoerceKeepBattery` and `BYDAutoChargingDevice.setChargePlanState` did NOT confirm while parked.)*
- **Locks, Windows, Wipers & Lights** (`bydauto.setting.BYDAutoSettingDevice`, `1` = On, `2` = Off; `0` ignored):
  - `locks.autoLock`: `getOverspeedLock()` / `setOverspeedLock(int)`
  - `locks.rainCloseWindows`: `getRainAutoLockWindowState()` / `setRainAutoLockWindowState(int)`
  - `locks.lockCloseWindows`: `getLockCarRiseWindow()` / `setLockCarRiseWindow(int)`
  - `vehicle.autoWiper`: `getAutoRainWiperState()` / `setAutoRainWiperState(int)`
  - `light.courtesyLight`: `getSmartWelcomeLightState()` / `setSmartWelcomeLightState(int)`
- **Head-Up Display (HUD)**:
  - `hud.height`: `BYDAutoSettingDevice.getHudHeight()` / `setHudHeight(int)` — raw `1..21` maps to UI `-10..+10` (offset `-11`).
  - `hud.brightness`: `BYDAutoSettingDevice.getHudBrightness()` / `setHudBrightness(int)` — raw `1..11` = UI `1..11`.
  - `hud.angle`: `BYDAutoSettingDevice.getHudRotationAngle()` / `setHudRotationAngle(int)` — raw `1..11` maps to UI `-5°..+5°` (raw `6` = `0°`, offset `-6`).
  - `hud.adas`: `caradapter.setting.getHudDisplayContentDriver()` / `setHudDisplayContentDriver(int)` — `1` = On, `2` = Off.
  - `hud.navi`: `caradapter.setting.getHudDisplayContentNavigation()` / `setHudDisplayContentNavigation(int)` — `1` = On, `2` = Off.
- **Trip Odometers (read-only verified)**:
  - `trip.aKm`: `caradapter.general.getMileageNumberOne()` (`6975.4 km` on 20 Nov 2026)
  - `trip.bKm`: `caradapter.general.getMileageNumberTwo()` (`7784.2 km` on 20 Nov 2026)
- Display on/off events: bind `com.ts.appservice.power/.CarPowerService` (action `com.ts.intent.action.ts.carpower`), `ICarPowerBinder` transaction `1` = register listener (interface token + strong binder). Listener `com.ts.lib.power.ICarPowerEventListener` gets transaction `4` with ints (type, panel, value); type `2` + panel `1` = main display, value `1` = on, `2` = off.
- Broadcast `com.byd.autoservice.ACTION_POWER` with boolean extra `power_mode` (true = on) is also sent on wake.
- SharkOS-CE's `wakehelper` module (package `tunein.player`) uses this to restart SharkOS-CE after standby.

---

### Additional powertrain, chassis & battery signals (8 Nov)

Added to `shark6_codes.json` (READ ONLY), values read 8 Nov parked & charging:

| Signal | Call | Parked value / note |
| --- | --- | --- |
| engine.rpm | `cloud.getEngineSpeed()` (also body, bydauto engine) | 0 (engine off) |
| motor.frontRpm / rearRpm | `cloud.getDriverMotorSpeed(1 / 2)` | 14999 / 29999 stopped => zero = 15000 / 30000 (offsets applied); scale unverified |
| motor.frontTorque / rearTorque | `cloud.getDriverMotorTorque(1 / 2)` | 5000 / 0 stopped (front offset -5000 applied) |
| motor.frontTemp / rearTemp | `cloud.getDriverMotorTemperature(1 / 2)` | 0 / -4 (units?) |
| drive.mode | `setting.getDriveMode()` | 1 = Normal |
| drive.regenLevel | `setting.getEnergyFeedback()` | 2 = High |
| chassis.autoHold | `bydauto adas.getAVHState()` / `assist.getAutoParkingState()` | 0 when OFF |
| chassis.stability | `assist.getElecCarBodyStablySysState()` | 0 when ON. Safety system: never write |
| chassis.steeringAngle | `body.getSteeringWheelValue(1)` ((2) = rate) | 65490 = unsigned 16-bit -46 (≈ -4.6°) |
| chassis.slope | `sensor.getSlope()` | 255 (no value?) |
| pedal.accelerator / brake | `sensor.getAccelerateDeepness()` / `getBrakeDeepness()` | 0 |
| trip.distance/time/fuel/elec | `general.getCurrentTrip{Mileage,Time,Fuel,Elec}()` | 0 |
| env.lightIntensity | `sensor.getLightIntensity()` | 5 in daylight |
| trailer.mode | `setting.getTrailerModeState()` | 2 = off |

Not reachable through `caradapter` (handled via `OtaTsUpdateService`):
- **12 V battery voltage / battery temp** (`battery_service`): bind `com.ts.ota.otasdkmgr/.OtaTsUpdateService`
  (action `android.Otasdk.OtaTsUpdateService`), `IOtaSdkManager.Stub.asInterface`, then `getBatteryVoltage(1)`
  (`13` V), `getBatteryTemp(0/1)`, `getSocValue()`, `getChargeState()`, `getDischargeState(1 = V2L)`.
- **Engine load/torque/RPM "diagnostic cache"**: Android `CarDiagnosticManager.getLatestLiveFrame()`,
  `getSystemFloatSensor(8)` = RPM, (0) = calculated load, `getSystemIntegerSensor(24/25/26)` = torque %.

**Done in v0.6.0 (verified parked, 8 Nov 2026):** the OTA backend is in (`CarAdapterBackend`, adapter name
`battery_service`). `getBatteryVoltage(1)` returns **13 = volts** (no scale), `getBatteryTemp` 29 = °C. Body
`getTyreTemperatureValue(1..4)` read 68 for all four at 29 °C outside: **raw = °C + 40**. The car HAS HUD snow mode,
cargo light, mirror auto-fold, screen rotation and an SOC-hold switch (owner confirmed), but the method names in the
signal map are wrong for this firmware (`getHudSnowMode`, `getCargoLightState`, `getRearviewMirrorFollowFold`,
`getScreenRotation`, `energy.socSave` all "no matching method"). TODO: find the real calls with the explorer's Record. `getSOCTarget` read 65. `setTrailerDragType` accepts 1..3 only
and only while trailer mode reads 1 (on).

### Media keys, mobile data and lifetime counters (13 Nov)
- **Spotify (`com.spotify.music`, AAOS build 4.4.1.0)** rejects `MediaController` transport commands from SharkOS-CE (`E Spotify: Failed to validate package`). A system media key works: `input keyevent 126` (play), `127` (pause), `87`/`88` (next/previous) from adb shell. It goes to Android's current media-button session.
- **Mobile data** is the T-Box: `ConnectivityManager` shows a network of type `Tbox` (interface `vlan3`) with the `CELLULAR` transport. Android telephony has no modem (service state `OUT_OF_SERVICE`, signal strength null), so signal level and 4G/5G are not readable that way.
- **Lifetime counters** (`caradapter.general`): `getEVMileageValue` + `getHEVMileageValue` = odometer (14059 + 3447 = 17506 on 7 Nov); `getTotalElecConValue` kWh (3612.3); `getTotalFuelConValue` L (354.5). `getTotalFuelConPHMValue` (2.0) is litres over ALL km, which is why BYD's fuel figures look optimistic. `getBatteryPackEnergy` read 144 (unit unknown, unused).

### Drive tab findings (14 Nov 2026, car parked, in P)
- `trailer.limitSmall/Medium/Large` = `setting.getTrailerSmall/Middle/LargeLimitWeight` read **749 / 1499 / 2500** (kg, weight classes). `getTowingProhibitedState` reads 3 (meaning unknown).
- Tyre temperature offset confirmed: raw 67 at 27 C.
- `setting.getDriveMode` has **no setter** (no `setDriveMode`), so Eco/Normal/Sport can only be shown, not set.
- Writes that returned 0 but did **not** change while parked: `setBrakePedalMode(2)`, `setSteerAssis(2)`, `setEnergyFeedback(1)`, `setTrailerModeState(1)` (size write returns 1 = refused while mode is off). Retest with the car switched on.
- `getGoalRoadMode` only known value: 1 = Normal. Other terrain codes are taught by the driver (hold a tile).
- Accelerometer (ASM330LHH) lies flat: reads (0.02, -0.16, +9.87). z is up, x/y horizontal; which of x/y is forward is learned from driving. No rotation-vector/magnetometer sensor exists, so there is no compass while parked.
- `chassis.sandAbs` reads 1 in Normal terrain (meaning unknown); `chassis.slope` reads 255 (no value).

### ESC (stability control) write, found 14 Nov 2026
BYD's quick-settings tile `com.android.systemui.qs.tiles.carcontrol.EspOff` (in BydCarSystemUI) calls `CarController.setElecCarBodyStablySysState(1)` to turn ESC **off** and `(2)` to turn it **on**; it reads state with `getElecCarBodyStablySysState` (0 = ON, 1 = OFF) and can show a double-confirm dialog first. SharkOS-CE sends the same call to `caradapter.assist` from the Drive tab only. Parked in P the off write returned 0 but the state stayed 0 (ESC on).
BydCarSettings also has `setTerrainModeAebOffConfirm` and a Wading/Sand risk dialog: those terrain modes switch AEB off with BYD's own confirmation, so SharkOS-CE never writes Wading or Sand.

### Terrain mode codes (verified 14 Nov 2026, scroll wheel on the car)
`caradapter.setting.getGoalRoadMode`: **Normal 1, Snow 2, Mud 3, Sand 4, Mountain 5**. Eco and Sport leave it at 1 (they are `getDriveMode`: Eco 0, Normal 1, Sport 2). Wading code not seen (switched on in BYD settings). SharkOS-CE only displays terrain; it does not write it.

### Verified by comparing BYD's own screens with raw reads (14 Nov 2026)
| Setting | BYD screen | `getXxx` raw | write via `bydauto.setting.BYDAutoSettingDevice` |
|---|---|---|---|
| Brake Assist Mode (`getBrakePedalMode`) | Comfort / Sport | 0 / 1 | `setBrakePedalMode(2)` = Comfort, `(1)` = Sport (`(0)` does nothing) |
| Steering Assist Mode (`getSteerAssis`) | Comfort / Sport | 1 / 3 | `setSteerAssis(1)` / `(3)` |
| Regenerative Braking (`getEnergyFeedback`) | Standard / High | 2 / 3 | `setEnergyFeedback(2)` / `(3)` |
The `caradapter.setting` setters return 0 but do not change the car; the `bydauto` ones do.
BYD Vehicle page tabs: Intelligent Chassis, Light, Comfort Driving (Auto Wiper, **Wading Protection**, **Sand ABS**, Auto-fold mirrors), HUD, Instrument, A/C, Seats, Locks, Notifications, **Towing Mode** (Trailer mode with a safety confirmation dialog, Trailer Weight 0-749 / 750-1499 / 1500-2500 kg, Automatic Indicator Detection), Cabin Perception.
BYD quick settings (swipe down): sliders Media / Navi / Center Screen / Instrument brightness; Vehicle Control tiles (HUD, Vent/Heating, Electric defroster, HDC, Lane Assist, Audio; more available: Regenerative Braking, Energy Management, DMS, CPD, EPB, SLW, AVH, Window lock, Wireless Charging, Auto Indoor Light, AFL, Interior Lights ON/OFF, BSD, TSR, ESC OFF); Shortcut tiles (Wi-Fi, Bluetooth, Connected Devices, Dark Mode, Screen OFF, Auto-rotate; more: Cellular Data, Mute, Screenshot); SAVE (battery hold 25-70%).
Other BYD apps: Rage Mode `com.byd.dlc.drivingmode/com.byd.drivingmode.MainActivity` (low-speed climbing mode, disables HDC, asks Agree/Cancel), Smart Charging `com.byd.smarttravel/.MainActivity` (scheduled charging, 23:00 to 100%, off), Scene Mode `com.byd.scenemode/.MainActivity` (Camping), My Car `com.byd.mycar/.StartActivity` (locks, trunk, windows, mirror fold, seat vent/heat, DRL, trunk light), Data Monitor `com.byd.trafficmonitor/.MainActivity` (SIM data 2 GB/month), AVM 360 camera `com.byd.avm/.MainActivity`, Photos `com.byd.auto_photo`, Manual `com.byd.auto_emanual`.

## Cameras (verified 16 Nov 2026, in Park)

- Android's camera service lists no cameras; `/dev/video51-58` are `system:camera` and closed to apps and to adb shell. Apps cannot read `/vendor/lib64/libais_client.so` (SELinux). **The adb shell user can**, through the Qualcomm AIS/QCarCam library, if it is opened in the `sphal` linker namespace (`libdl_android.so` `android_get_exported_namespace("sphal")` + `android_dlopen_ext`).
- `/data/local/tmp` is `data_local` here and blocks shell executables. Run helpers from `/data/user_de/0/com.android.shell/<dir>/` (`shell_data_file`). Ship the helper as a jniLib executable (`useLegacyPackaging = true`) and copy it from `nativeLibraryDir` with `cp`.
- Inputs (`qcarcam_query_inputs`; entries are **320 bytes**, not the public header's 316): ids 0,1,2,3,4,5,8,9, UYVY 8-bit (`0x7080102`), 30 fps. Real cameras (1920x1300): **8 front, 5 rear, 9 driver side, 4 passenger side**. Ids 1, 2, 3 time out (no signal); 0 is a 1280x800 test gradient. Frame buffers: `/dev/ion` legacy ioctl, heap mask `0x2000000`, passed with `QCARCAM_BUFFER_FLAG_OS_HNDL|CACHE`.
- Always stop, close and uninitialize; never take the master role. The 360° app (`com.byd.avm`) blanks `screencap`.
- Code: `app/src/main/cpp/sharkcam.cpp` (`sc list | snap | record`), `dashcam/DashCamEngine.kt`, `ui/screens/DashCamScreen.kt`. Reference for the approach: the OverDrive project's Shark 6 / DiLink 5 support (PR #316).

## Calls BYD's own voice assistant makes (read from its APK, 21 Nov 2026; NOT yet tried on the car)

Source: `/product/app/BydVoice/BydVoice.apk` (`com.byd.vrassistant`), classes `com.byd.vrassistant.conversation.*Helper` (HvacSetStateHelper, VehicleBasicHelper, BydSdkHelper, ...). It calls the same `android.hardware.bydauto.*` devices SharkOS-CE uses, so the helpers are a source of write calls. Pulled with `adb pull`, disassembled with build-tools `dexdump -d`.

- **Windows (VERIFIED ON THE CAR 21 Nov 2026 for the front-left window):** `bodywork.BYDAutoBodyworkDevice.setWindowControlState(int[4])`. Sent `[1,0,0,0]` -> returned 0, window opened (caradapter `getWindowOpenPercentLeftFront` read 9, 88, 97 as it travelled; it is a slow move, so read-back needs ~8 s and the generic confirm reports false); `[2,0,0,0]` closed it again (back to 0). The percent read is 0-100. The rear-left small-gap code `5` returned 0 but the percent read stayed 0, so it is unconfirmed. Array index 0 = front left, 1 = front right, 2 = rear left, 3 = rear right; value 0 = leave alone. Codes from the requested percentage: `>90` -> `1` (open), `<5` -> `2` (close), `40-60` -> `4` (half), `5-20` -> `5` (small gap); other percentages are refused ("UNSUPPORTED"). It refuses while the window lock / child lock is on, and reads current state with `getWindowState(1..4)`.
- **Recirculate:** `ac.BYDAutoAcDevice.setAcCycleMode(1, 1)` / `(1, 0)` (the first argument is `1` here, BYD's climate app sends `0`); reads `getAcCycleMode()` first. Second argument `1` = recirculate, `0` = fresh, matching the corrected 21 Nov encoding.
- **Other writes in the same helpers:** `setAcControlMode(II)`, `setRearAcControlMode(II)`, `setAcCompressorMode(II)`, `setAcTemperatureControlMode(II)`, `setAcWindMode(II)`, `setAcMaxCoolingState(I)`, `setAcDefrostState(III)`, `setSeatHeatingState(II)` / `setSeatVentilatingState(II)`, `setSteeringWheelHeatingState(I)`, `setQuickCleanAirState(I)`, `doorlock.setDoorLockStatus(II)`, `doorlock.setRearWindowLock(I)` (1 = ... 2 = lock on, see helper), `setting.setHudState(I)`, `setScreenRotation(I)`, `fragrance.setFragranceConcentration(I)`, `energy.setOperationMode(I)`.
- The assistant's engine is Cerence ARK (`cerence.ark.assistant.*`); its controller domain `vehiclecontrol` also has interior light, camera, drive/road mode and rear-tray (`voiceCtlBackDoor(1)` open, `(3)` close, `getBackDoorCurState` read; decoded only, not tried) handlers. The Shark 6 is a ute: no sunroof, boot or adjustable rear windows.

