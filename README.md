# Lumen — a minimalist TV launcher for Chromecast with Google TV

Lumen replaces the Google TV home screen with a calm, cinematic one: a dark backdrop, a large
featured area that follows focus, a Top Shelf of pinned apps, recently used apps, and a clean
grid of everything else. No ads, no recommendations, no rows you didn't ask for.

Built with Kotlin and Jetpack Compose, designed for the D-pad remote first.

## Features

| | |
|---|---|
| **App discovery** | Finds every TV (leanback) app; non-TV phone apps can be turned on in Settings. Updates live when apps are installed or removed. |
| **Top Shelf** | Pinned apps. Seeded on first run with popular streaming apps you have installed. |
| **Featured area** | Shows the focused app's name over a wash of its colors. Collapses when you browse further down, like tvOS. |
| **App grid** | One continuous grid of everything else below the Top Shelf, ending with a Settings tile, like tvOS. Every tile shares one 16:9 frame: the app's banner, or a card built from its icon. |
| **Remote control** | Every element is reachable with the D-pad. Select opens an app; **hold Select** for options (pin, reorder, hide, app info). Rows remember where you were. Home while on the home screen jumps back to the top. |
| **Settings** | Wallpaper, ambient backdrop, clock, non-TV apps, hidden apps, Home-button behavior, a shortcut to Android Settings. |
| **Wallpapers** | Six built-in gradients, or your own photos (pushed over ADB or imported with the system picker if your device has one). |
| **Home integration** | Registered as a HOME app, plus an optional accessibility service that makes the remote's Home button open Lumen on Google TV. See [docs/HOME_BUTTON.md](docs/HOME_BUTTON.md). |

## Project structure

```
app/                  Activity, HOME intent filters, navigation between screens, dependency wiring
core/designsystem/    Colors, type, spacing, focus-aware components (AppTile, MenuRow, …), wallpaper rendering
core/apps/            App discovery (PackageManager), icon/banner rasterizing, launching
core/settings/        DataStore-backed preferences, wallpaper model and library
core/system/          Home-button override (accessibility service), shortcuts into system screens
feature/home/         Home screen: hero, Top Shelf, app grid, app options dialog
feature/settings/     Settings screen, wallpaper picker, hidden apps
```

Dependencies only point inward: features depend on `core/*`; `core` modules don't depend on features.

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
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

`./gradlew assembleRelease` also works and is signed with the debug key for easy sideloading.
Use your own signing config before sharing the APK with anyone.

## Install on the Chromecast over Wi-Fi

1. **Enable Developer options:** Settings → System → About → select **Android TV OS build** seven
   times until it says you're a developer.
2. **Enable debugging:** Settings → System → Developer options → turn on **USB debugging**.
   On Chromecast with Google TV this also enables network ADB.
   (On Google TV Streamer and other Android 14 devices, use **Wireless debugging** and
   `adb pair <ip>:<pairing-port>` first.)
3. **Find the IP address:** Settings → Network & Internet → your Wi-Fi network.
4. **Connect** from your computer (same network):
   ```sh
   adb connect 192.168.1.50:5555
   ```
   Approve the **Allow USB debugging?** prompt on the TV (tick *Always allow*). Check with
   `adb devices`; it should say `device`, not `unauthorized`.
5. **Install and open:**
   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n app.lumen.launcher/.MainActivity
   ```
   Lumen also appears in the Google TV apps list.

### Make the Home button open Lumen

Google TV has no setting to change the default home app. Lumen includes an accessibility
service that brings Lumen back whenever the Google TV home appears. To turn it on:

- **On the TV:** Lumen → Settings → *Home Button Service* → turn on **Lumen Home Button**.
  (Or Settings → System → Accessibility → Lumen Home Button.)
- **Or over ADB:**
  ```sh
  adb shell settings put secure enabled_accessibility_services app.lumen.launcher/app.lumen.core.system.HomeOverrideService
  adb shell settings put secure accessibility_enabled 1
  ```
  This **replaces** the list of enabled accessibility services. If you use others (e.g. Button
  Mapper), first run `adb shell settings get secure enabled_accessibility_services` and append
  Lumen's entry with a `:` separator.

[docs/HOME_BUTTON.md](docs/HOME_BUTTON.md) covers why this approach was chosen, the
alternatives, and how to test it.

### Add your own wallpapers

```sh
adb push my-photo.jpg /sdcard/Android/data/app.lumen.launcher/files/wallpapers/
```

Then pick it in Settings → Wallpaper. JPG, PNG and WebP work; 3840×2160 or 1920×1080
looks best.

## Restore the original Google TV launcher

Lumen never modifies or disables the Google TV launcher by default, so restoring it is simple.
Start at step 1 and stop when things are back to normal:

1. **Turn off the redirect:** Lumen → Settings → *Open Lumen with Home* → Off.
   Home now goes to Google TV.
2. **Turn off the service:** Settings → System → Accessibility → Lumen Home Button → Off.
   Or: `adb shell settings delete secure enabled_accessibility_services`
   (this clears *all* enabled services; re-add any others you use).
3. **Uninstall Lumen:**
   ```sh
   adb uninstall app.lumen.launcher
   ```
   Or Settings → Apps → Lumen → Uninstall. This also removes the service.
4. **Only if you followed the advanced "disable Google TV home" route** in
   [docs/HOME_BUTTON.md](docs/HOME_BUTTON.md), re-enable it:
   ```sh
   adb shell pm enable com.google.android.apps.tv.launcherx
   adb shell pm enable com.google.android.tungsten.setupwraith
   adb reboot
   ```

**If the TV ever seems stuck:** ADB still works no matter what the screen shows. Connect with
`adb connect <ip>:5555` and run steps 2–4. You can always reach Android Settings directly with
`adb shell am start -a android.settings.SETTINGS`. A factory reset (hold the button on the back
of the Chromecast) is the last resort and is never needed for the default setup.

## Development tips

- **Emulator:** create an *Android TV (1080p)* device with a Google TV system image in Android
  Studio's Device Manager. Use the arrow keys and Enter; hold Enter for options.
- **Logs:** `adb logcat --pid=$(adb shell pidof app.lumen.launcher)`
- **Screenshot:** `adb exec-out screencap -p > screen.png`
- **Reset Lumen's settings:** `adb shell pm clear app.lumen.launcher`
- **Custom font:** drop an `.ttf` into `core/designsystem/src/main/res/font/` and set `Family`
  in `Tokens.kt`; Inter or SF-like fonts fit the design.
