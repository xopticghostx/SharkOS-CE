# Contributing

Thanks for helping out. Useful contributions include:

- **Signal mapping:** new signals, read and write codes, or values confirmed on other firmware versions.
- **Widgets and gauges** you'd like to see.
- **Bug reports** with the firmware version and what the car actually did.

## Licence of contributions

SharkOS CE is licensed under the GNU General Public License, version 3. By submitting a pull request you agree that your contribution is licensed under the same terms, and that you have the right to submit it.

Only contribute code you wrote yourself or code under a GPL-3.0-compatible licence, and say where it came from. Don't paste decompiled code from BYD's apps or from other people's apps. Method names, value codes and descriptions of how something works are fine.

## Reporting signal findings

Include:

1. Firmware version (Settings › About)
2. The service or class (for example `BYDAutoAcDevice`, or the `CarAdapterService` hvac adapter) and the method
3. The arguments used, and the value read back before and after
4. Whether you confirmed the result on the car

The Car Explorer makes this easy: press Record, change the setting in BYD's own menu, then Export.

## Rules for changes

- **Never add writes to safety systems:** ABS, ESC/ESP, traction control, AEB, emergency steering or braking, airbags, collision warnings. Never weaken `StartupMemory.SAFETY`. Pull requests that do will be closed.
- **Don't invent car facts.** Tank sizes, method names and value codes must come from `shark6_codes.json`, `BYD_SHARK6_REFERENCE.md` or a Car Explorer export. If something isn't confirmed, show `--`.
- **Say how it was tested.** Mark each change as verified on the car, or "emulator only, not verified on the car".
- **Changelog and version.** Add a `CHANGELOG.md` entry and bump `versionCode` and `versionName` in `app/build.gradle.kts` for each batch of changes. List open questions in the entry.
- **Put signals in the JSON.** Prefer editing `shark6_codes.json` over hard-coding methods in Kotlin, and record verified encodings in its `_note` fields.
- **Keep private data out.** Don't commit keystores, VINs, GPS traces, IP addresses or raw car exports.
