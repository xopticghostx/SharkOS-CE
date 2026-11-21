# Contributing

Thanks for helping out. SharkOS CE is built around one Shark 6 on one firmware, so the most valuable contributions are things you have **checked on a real car**.

Useful contributions:

- **Signal findings:** new read and write codes, or values confirmed (or different) on another firmware version.
- **Bug reports** with the firmware version and what the car actually did.
- **Widgets, gauges and controls** you would like to see, or have built.
- **Research** into how BYD's own apps talk to the car. See the [voice assistant guide](docs/BYD_VOICE_ASSISTANT.md) for one method that works well.

## Look after the car

This app writes to a real vehicle. When you test:

- Work **parked**, change **one thing at a time**, note the starting value, and put it back afterwards.
- Never test writes to safety systems (ABS, ESC/ESP, traction control, AEB, emergency steering or braking, airbags, collision warnings) or terrain modes on a moving car. SharkOS CE has no built-in safety blocklist (the maintainer's decision, see issue #7), so the responsibility is yours.
- Keep people and objects clear of anything that moves (windows, mirrors, seats).
- The app must **never change a setting by itself**. A car write happens only when the user presses a control or ticks Remember in Startup memory. Pull requests that add silent, automatic or background writes will be closed.

## Licence of contributions

SharkOS CE is licensed under the GNU General Public License, version 3. By submitting a pull request you agree that your contribution is licensed under the same terms, and that you have the right to submit it.

Only contribute code you wrote yourself or code under a GPL-3.0-compatible licence, and say where it came from. **Don't paste decompiled code from BYD's apps or from other people's apps, and don't add BYD's files.** Method names, value codes and descriptions of how something works are fine.

## Reporting signal findings

Include:

1. Firmware version (Settings > About)
2. The service or class (for example `BYDAutoAcDevice`, or the `CarAdapterService` hvac adapter) and the method
3. The arguments used, and the value read back before and after
4. **How you confirmed it:** the read-back alone is not enough. Check BYD's own screen or the physical result. (A recirculate switch once shipped inverted even though every read-back matched our own table.)

The Car Explorer makes this easy: press Record, change the setting in BYD's own menu, then Export.

## Build and test

- **Build:** see [Build from source](README.md#build-from-source). You do not need a signing key; without `keystore.properties` a release build falls back to the debug key. Never publish or share such a build as a release.
- **Test on the car:** use adb over Wi-Fi. The app has a built-in test hook that can open any screen, read signals, reload a signal file and do a write-and-restore check (`cmd=verify`). Changes to `shark6_codes.json` can be tried without rebuilding by using an override file. Both are explained step by step in the [voice assistant guide](docs/BYD_VOICE_ASSISTANT.md).
- **This head unit hides log lines** below error level for app tags. The app logs car writes with `Log.e` under the tag `SharkCar`, so read them with `adb logcat -d -s SharkCar:W`.
- **Emulator:** it has no car services, so anything car-related is "emulator only, not verified on the car".

## Rules for changes

- **Don't invent car facts.** Tank sizes, method names and value codes must come from `shark6_codes.json`, `BYD_SHARK6_REFERENCE.md` or a Car Explorer export. If something isn't confirmed, show `--`.
- **Say how it was tested.** Mark each change as verified on the car (and what you checked it against) or "emulator only, not verified on the car". Mark codes you haven't tried as unverified in the signal's `_note`.
- **Put signals in the JSON.** Prefer editing `app/src/main/assets/shark6_codes.json` over hard-coding methods in Kotlin, and record verified encodings in its `_note` fields. Some BYD writes report slowly (3 to 5 seconds; windows about 8), so set `confirmMs` instead of treating a late confirm as a failure.
- **Follow the existing look.** New screens, cards and controls use the design kit (`ui/components/DesignKit.kt`) and the colour tokens in `ui/Theme.kt`. Don't hard-code colours, and match the layout, type sizes and spacing of existing screens. UI sizes are in px on a canvas at least 1920 wide and 1060 tall.
- **Keep private data out.** Don't commit keystores, VINs, GPS traces, IP addresses, Wi-Fi names or raw car exports. Screenshots must not show maps, coordinates or your address.
- **Versions and release notes.** Describe your change in the pull request. The maintainer bumps the version and writes the release notes, so please leave `versionCode` and `versionName` alone.

## Pull requests

- Keep each pull request to one topic, from a branch of your own.
- Say what you changed, which firmware and car you tested on, and what you checked it against.
- For visual changes, include a screenshot (with maps and personal details hidden).
- Questions or half-finished ideas are welcome as an issue first.
