# Root Freeze Shortcuts

A small root-only Android launcher helper. It creates proxy shortcuts for apps. When you tap a proxy shortcut, this app:

1. runs `pm enable --user 0 <package>` as root,
2. launches the target app by package name using root `monkey` first, then falls back to Android's enabled launcher intent,
3. watches the foreground app through `dumpsys`,
4. runs `pm disable-user --user 0 <package>` after the target is no longer foreground for the configured grace period.

This is meant for a rooted personal device, not Play Store distribution.

## Safety notes

Freezing critical packages can soft-lock parts of the system until you unfreeze them. The UI hides system apps by default. Avoid freezing launchers, keyboards, SystemUI, phone services, settings, package installer, Google Play services, root manager apps, and anything needed for unlock or recovery.

Recovery examples:

```sh
su -c 'pm enable --user 0 com.example.package'
```

From a computer:

```sh
adb shell su -c 'pm enable --user 0 com.example.package'
```

## How to use

1. Build and install the debug APK.
2. Open **Root Freeze Shortcuts**.
3. Grant root when prompted by your root manager.
4. Pick an app and tap **Pin shortcut**.
5. Tap the new home-screen shortcut instead of the app's normal launcher icon.

Settings:

- **Leave grace seconds**: how long the target must be out of foreground before freezing.
- **Launch timeout seconds**: if launch/foreground detection fails, the app refreezes after this timeout.
- **Show system apps**: disabled by default because it is easy to freeze something important.

## Build on GitHub Actions

Push this repository to GitHub and run **Build debug APK** from the Actions tab. The APK is uploaded as an artifact named `RootFreezeShortcuts-debug-apk`.

## Build locally

Install Android SDK platform 35, then run:

```sh
gradle --no-daemon :app:assembleDebug
```

The APK will be at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Implementation notes

- No AndroidX dependency.
- Java-only Android project.
- Uses pinned shortcuts on Android 8+.
- Uses a foreground service only while a launched target is being watched.
- Uses package-name validation before running root package-manager commands.
- Launches by package name instead of storing an explicit launcher activity. This avoids failures from stale/disabled aliases such as `com.instagram.android.IntentLauncher`.
