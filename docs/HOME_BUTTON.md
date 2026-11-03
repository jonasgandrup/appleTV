# Home button and startup on Chromecast with Google TV

## The problem

On a phone, installing an app with a `HOME` intent filter is enough: Android asks which home
app to use. Google TV doesn't work that way:

- The stock launcher (`com.google.android.apps.tv.launcherx`) is a privileged system app. Its
  `HOME` filter has a higher priority than any app you install, and Google TV has **no setting
  for choosing the default home app**.
- The remote's Home button sends `KEYCODE_HOME`. The system handles it before apps see it and
  starts the default home, which is always Google TV's.
- When the device boots, it starts that same default home.

So Atrium's `HOME` filter (in `app/src/main/AndroidManifest.xml`) is necessary but not
sufficient on Google TV. On AOSP Android TV builds and many other boxes it's enough on its own.

## Options

| Approach | Reliability | Risk | Used by Atrium |
|---|---|---|---|
| **A. Accessibility redirect** | High. Works after Home, boot, and leaving apps. A brief flash of Google TV is possible. | Low. Nothing system-level changes; turn it off any time. | **Yes (default)** |
| B. `cmd package set-home-activity` | Varies by firmware; often reset by reboots or updates. | Low | Optional, documented below |
| C. Disable the stock launcher | Highest: Atrium becomes the only home. | Medium. Some Google TV features stop working, and updates may re-enable it. | No; manual and advanced |

### A. Accessibility redirect (what Atrium does)

`core/system/.../HomeOverrideService.kt` is an `AccessibilityService` that listens only for
`TYPE_WINDOW_STATE_CHANGED` and reads only the event's **package name**
(`canRetrieveWindowContent="false"`). When the stock launcher's window comes to the foreground,
it starts Atrium's home activity:

- **Home button:** Google TV home appears, then Atrium is brought to the front right away.
- **Boot:** the system starts Google TV home, which triggers the same redirect. A bound
  accessibility service is exempt from Android 10+'s background-activity-start limits, so this
  works without a `BOOT_COMPLETED` receiver.
- **Back from an app** that returns to home: same path.
- Redirects are debounced (750 ms) so they can't loop.
- **Open Google TV Home** in Settings pauses the redirect until another app comes to the
  foreground, so you can still use profile switching and other Google TV-only features.
- **Open Atrium with Home** in Settings turns the redirect off without disabling the service.

Popular launchers such as Projectivy use this same approach on Chromecast with Google TV.

**To test on the device:**

1. Turn on the service (see [INSTALL.md](INSTALL.md)), then press Home from inside an app. Atrium should appear,
   possibly after a split second of Google TV.
2. Reboot (`adb reboot`). After the boot animation, Atrium should take over.
3. Settings → Open Google TV Home → Google TV stays. Open any app, press Home → Lumen.
4. If Home ever stops redirecting, check that the service is still on:
   `adb shell settings get secure enabled_accessibility_services`. Some updates turn
   accessibility services off.

### B. Set the preferred home activity (optional experiment)

```sh
adb shell cmd package set-home-activity app.lumen.launcher/.MainActivity
```

If your firmware respects this, Home goes straight to Atrium with no flash. Reports vary across
Chromecast updates: the setting is often ignored or reset after a reboot. It's harmless to try
alongside option A. To undo:
`adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity`,
or just uninstall Lumen.

### C. Disable the stock launcher (advanced, not recommended)

> Only do this after Atrium works well with option A, and with ADB working, so you can undo it.

```sh
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith   # fallback home on Google TV
```

With no other home available, Android uses Atrium for Home and boot. Known side effects: Google
TV recommendations, profiles, the "For you" tab, and some settings shortcuts that open inside
the Google TV launcher stop working. System updates may re-enable the packages. Undo it with
the `pm enable` commands in the restore section of [INSTALL.md](INSTALL.md).

Never run `pm uninstall` on system packages. It's much harder to undo than `disable-user`.

## Not yet verified on hardware

This prototype hasn't been tested on a physical Chromecast yet. Things to confirm:

- The package and window names the stock launcher reports on current firmware (adjust
  `StockLaunchers.packages` if they differ; `adb shell dumpsys window | grep mCurrentFocus`
  shows the foreground window).
- Whether option B persists across reboots on your firmware version.
- How long the Google TV flash lasts with option A.
