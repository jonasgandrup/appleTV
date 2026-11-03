# Install and development

How to build Atrium, put it on a Chromecast with Google TV, and take it off again.
[Back to the overview](../README.md).

## Requirements

- JDK 17 or newer
- Android SDK with platform 35 and build-tools (Android Studio installs these, or use the command-line tools below)
- `adb` (from Android SDK platform-tools)

### Installing the SDK without Android Studio (macOS)

```sh
brew install --cask android-commandlinetools
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties
export PATH="$ANDROID_HOME/platform-tools:$PATH"   # for adb
```

Or open the project in Android Studio, which writes `local.properties` for you.

## Build the APK

```sh
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Install this one on the TV:
it's optimized with R8 and is much smoother on the Chromecast than a debug build. It's signed with
the debug key for easy sideloading; use your own signing config before sharing the APK with anyone.

`./gradlew assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk` for development.

## Install on the Chromecast over Wi-Fi

1. **Enable Developer options:** Settings → System → About → select **Android TV OS build** seven
   times until it says you're a developer.
2. **Enable debugging:** Settings → System → Developer options → turn on **USB debugging**, then
   **Wireless debugging**. Open *Wireless debugging* → **Pair device with pairing code** and keep
   that screen open. Android 14 devices need this, including a Chromecast with Google TV that has
   updated to it. On older software, USB debugging alone opens port 5555; skip the pairing and
   connect to that port.
3. **Find the addresses:** the Wireless debugging screens show the IP address and port for pairing,
   and a different port for connecting. The connecting port can change after a reboot.
4. **Pair and connect** from your computer (same network):
   ```sh
   adb pair 192.168.1.50:<pairing-port>     # asks for the 6-digit code
   adb connect 192.168.1.50:<connect-port>
   ```
   Check with `adb devices`; it should say `device`, not `unauthorized`. If these fail with a
   connection error while the TV is clearly reachable, restart adb with `adb kill-server` and retry.
5. **Install and open:**
   ```sh
   adb install -r app/build/outputs/apk/release/app-release.apk
   adb shell am start -n app.lumen.launcher/.MainActivity
   ```
   Atrium also appears in the Google TV apps list.

### Make the Home button open Atrium

Google TV has no setting to change the default home app. Atrium includes an accessibility
service that brings Atrium back whenever the Google TV home appears. To turn it on:

- **On the TV:** Atrium → Settings → *Home Button Service* → turn on **Atrium Home Button**.
  (Or Settings → System → Accessibility → Atrium Home Button.)
- **Or over ADB:**
  ```sh
  adb shell settings put secure enabled_accessibility_services app.lumen.launcher/app.lumen.core.system.HomeOverrideService
  adb shell settings put secure accessibility_enabled 1
  ```
  This **replaces** the list of enabled accessibility services. If you use others (e.g. Button
  Mapper), first run `adb shell settings get secure enabled_accessibility_services` and append
  Atrium's entry with a `:` separator.

[HOME_BUTTON.md](HOME_BUTTON.md) covers why this approach was chosen, the
alternatives, and how to test it.

### Add your own wallpapers

```sh
adb push my-photo.jpg /sdcard/Android/data/app.lumen.launcher/files/wallpapers/
```

Then pick it in Settings → Wallpaper. JPG, PNG and WebP work; 3840×2160 or 1920×1080
looks best.

### Add your profile picture

Google doesn't share a signed-in account's photo with other apps, so add your own:

```sh
adb push me.jpg /sdcard/Android/data/app.lumen.launcher/files/profile.jpg
```

It appears next to the clock the next time Atrium starts.

## Restore the original Google TV launcher

Atrium never modifies or disables the Google TV launcher by default, so restoring it is simple.
Start at step 1 and stop when things are back to normal:

1. **Turn off the redirect:** Atrium → Settings → *Open Atrium with Home* → Off.
   Home now goes to Google TV.
2. **Turn off the service:** Settings → System → Accessibility → Atrium Home Button → Off.
   Or: `adb shell settings delete secure enabled_accessibility_services`
   (this clears *all* enabled services; re-add any others you use).
3. **Uninstall Atrium:**
   ```sh
   adb uninstall app.lumen.launcher
   ```
   Or Settings → Apps → Atrium → Uninstall. This also removes the service.
4. **Only if you followed the advanced "disable Google TV home" route** in
   [HOME_BUTTON.md](HOME_BUTTON.md), re-enable it:
   ```sh
   adb shell pm enable com.google.android.apps.tv.launcherx
   adb shell pm enable com.google.android.tungsten.setupwraith
   adb reboot
   ```

**If the TV ever seems stuck:** ADB still works no matter what the screen shows. Connect with
`adb connect <ip>:<connect-port>` (or port 5555 on older software) and run steps 2–4. You can always reach Android Settings directly with
`adb shell am start -a android.settings.SETTINGS`. A factory reset (hold the button on the back
of the Chromecast) is the last resort and is never needed for the default setup.

## Project structure

```
app/                  Activity, HOME intent filters, navigation between screens, dependency wiring
core/designsystem/    Colors, type, spacing, liquid glass, focus-aware components (AppTile, MenuRow, …), wallpaper rendering
core/apps/            App discovery (PackageManager), icon/banner rasterizing, launching
core/settings/        DataStore-backed preferences, wallpaper model and library
core/system/          Home-button override (accessibility service), shortcuts into system screens
feature/home/         Home screen: stage, top row, Continue Watching, app grid, Control Center, screen saver
feature/settings/     Settings screen, wallpaper picker, hidden apps
```

Dependencies only point inward: features depend on `core/*`; `core` modules don't depend on features.

## Development tips

- **Emulator:** create an *Android TV (1080p)* device with a Google TV system image in Android
  Studio's Device Manager. Use the arrow keys and Enter; hold Enter for options.
- **Logs:** `adb logcat --pid=$(adb shell pidof app.lumen.launcher)`
- **Screenshot:** `adb exec-out screencap -p > screen.png`
- **Reset Atrium's settings:** `adb shell pm clear app.lumen.launcher`
- **Custom font:** drop an `.ttf` into `core/designsystem/src/main/res/font/` and set `Family`
  in `Tokens.kt`; Inter or SF-like fonts fit the design.
