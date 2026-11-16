# Disclaimer

SharkOS CE is an unofficial, community-built project. It is not affiliated with, endorsed by, or supported by BYD.

- **Use at your own risk.** The app talks to vehicle systems through undocumented head-unit interfaces. Behaviour can change between firmware versions. The software comes with no warranty, as set out in the [GPL-3.0 licence](LICENSE), and the author accepts no liability for damage, warranty issues or loss of vehicle functionality.
- **Driver-assist settings are your responsibility.** Advanced Settings can switch off Lane Assist, the speed limit warning chime, Driver Monitoring and Child Presence Detection, and can do so automatically at every start. Check that this is legal where you drive and that it doesn't affect your insurance. Leave Child Presence Detection on if children ever travel in the car.
- **Core safety systems are off limits.** A hard-coded blocklist prevents the app from writing to ABS, traction control, AEB, airbags, collision warnings and emergency steering or braking. Don't remove it or work around it in forks or pull requests.
- **One exception: ESC.** ESC can be switched off and on from the Drive tab, using the same call as BYD's own quick-settings tile. It only works at 10 km/h or less, needs a two-step confirm, and is never written automatically or at startup. Driving with ESC off is your responsibility.
- **Don't operate it while driving.** Set things up while parked.
- **The wake helper reuses a package name.** It installs under the application ID `tunein.player` so the head unit doesn't stop it at standby. It is not the TuneIn app, has no connection to TuneIn, and can't be installed alongside it.
- **The installer and permission manager use the head unit's own ADB.** Only install APKs you trust. Installing through the app grants every permission the APK asks for.
- **Firmware tested:** `SOC_260811_S`. Other versions are untested.
- Third-party names (BYD, DiLink, TuneIn, Android, Spotify and others) belong to their owners.
