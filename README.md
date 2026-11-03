# Atrium — a minimalist TV launcher for Chromecast with Google TV

Atrium replaces the Google TV home screen with one modelled on Apple TV's: a full-screen stage
showing what's in the focused app, a top row of six apps on frosted glass, a grid of everything else
below, and a Control Center behind the clock. No ads, no recommendations, no rows you didn't ask for.

Built with Kotlin and Jetpack Compose, designed for the D-pad remote first.

## Features

| | |
|---|---|
| **App discovery** | Finds every TV (leanback) app; non-TV phone apps can be turned on in Settings. Updates live when apps are installed or removed. |
| **Top row** | Six pinned apps, like tvOS's. Seeded on first run with popular streaming apps you have installed. Moving a seventh in hands the last one down to the grid. |
| **Stage** | The focused app's featured artwork, full bleed, with the program's title at the top left. Apps with no artwork light it in their own color instead, under their name; black and white brands leave your wallpaper showing. As on tvOS, artwork has the stage to itself at rest; Up brings in the title and the app's row. |
| **Up Next** | The app's Watch Next row above the top row, modelled on Continue Watching in Apple's TV app: wide 16:9 tiles with progress bars, and the title, episode and time left on the focused one. Rechecked when you come back from an app. An app that publishes only small thumbnails, like YouTube, gets a Recommended row in the same place instead of a blurry full-screen picture. |
| **Continue Watching** | One row under the top row with what you're in the middle of across all your apps, most recently watched first, whichever tile you're on. Rechecked when you come back from an app. Hold select on a program to remove it from Atrium, or to open its app. Apps' own lists stay as they are, and a removed program comes back once you watch it again. |
| **Full-screen Top Shelf** | Up from the dock (or from Up Next, if the app has it) slides the dock away and shows what the app features, one program at a time, with Play and page dots. Left and Right change program. Apps fill it from their preview channels, or from their Watch Next if that's all they publish. Only apps whose art is too small for it, like YouTube, go without. |
| **Folders** | Hold Select on an app, then Move to: New Folder, an existing folder, the top row or the home screen. Select a folder's name to rename it, as on tvOS. A folder goes away with its last app. |
| **Control Center** | Press Up to the clock, then Select: loose glass tiles in the corner for Sleep, Wi-Fi, Screen Saver, Sound, Settings, your profile, Search, Accessibility and Android Settings. With the Home Button service on, holding Home opens it too, in place of Google TV's dashboard. Sleep also needs that service. |
| **App grid** | tvOS's six-column grid and 5:3 tiles below the top row, over a frosted copy of the stage, ending with a Settings tile. Every tile shares one frame: the app's banner, or a card built from its icon. |
| **Liquid glass** | Glass on controls only, as Apple's guidelines ask: the top row, clock, menus, Control Center and buttons, frosting whatever is really behind them. Edges bend in the color around them, the sheet has a double rim for thickness, and the top row's highlight glides along to the focused app. Frost comes from images blurred once on the CPU rather than a live blur or refraction shader, and the backdrop is baked into one bitmap, to spare the Chromecast's GPU. |
| **Motion & sound** | After a few quiet seconds the focused tile expands and gently sways, as on tvOS. Tiles lift and lean into D-pad presses; apps zoom open out of their tile. Soft navigation clicks, which can be turned off. |
| **Screen saver** | After a few idle minutes on the home screen, or with Back on the first app in the top row, as on tvOS: Aerials, original nature scenes as perfectly looping video (rendered by `tools/render_aerials.py`, so there's no footage to license), or your photos. The time stays on screen and changes sides with each scene; turn that off in Settings to see it only on Down, as on tvOS. Up picks a category, Left and Right change scene, Down names it, and anything else stops it. |
| **Remote control** | Every element is reachable with the D-pad. Select opens an app; **hold Select** for options (Edit Home Screen, Move to, hide, delete, app info). Back climbs to the top row from anywhere. Rows remember where you were, and so does the home screen when you come back from Settings. Home while on the home screen jumps back to the top. |
| **Settings** | A plain list as on tvOS, explaining the focused row under the icon: wallpaper, Top Shelf artwork, clock, screen saver, click sounds, your name (its initial stands in for a profile picture, unless you push a photo as described below), non-TV apps, hidden apps, Home-button behavior, a shortcut to Android Settings. |
| **Wallpapers** | Seven built-in gradients, or your own photos (pushed over ADB or imported with the system picker if your device has one). |
| **Home integration** | Registered as a HOME app, plus an optional accessibility service that makes the remote's Home button open Atrium on Google TV. See [docs/HOME_BUTTON.md](docs/HOME_BUTTON.md). |

## Project structure

```
app/                  Activity, HOME intent filters, navigation between screens, dependency wiring
core/designsystem/    Colors, type, spacing, liquid glass, focus-aware components (AppTile, MenuRow, …), wallpaper rendering
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

[docs/HOME_BUTTON.md](docs/HOME_BUTTON.md) covers why this approach was chosen, the
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
- **Reset Atrium's settings:** `adb shell pm clear app.lumen.launcher`
- **Custom font:** drop an `.ttf` into `core/designsystem/src/main/res/font/` and set `Family`
  in `Tokens.kt`; Inter or SF-like fonts fit the design.
