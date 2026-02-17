# Tang!

Tang! relays Android notification-based messages and call alerts to a macOS menu bar app over local network.

## Projects

- `macos-menubar`: SwiftUI menu bar app with embedded WebSocket server, pairing QR, Keychain token auth, and local notifications.
- `android-app`: Kotlin Android app with `NotificationListenerService`, QR pairing, secure token storage, and WebSocket relay client.
- `shared/protocol.md`: JSON protocol and pairing payload contract.

## Platform Downloads

### macOS app (DMG)

- Download the latest DMG from GitHub Releases: `https://github.com/yangtaengg/Tang/releases/latest`
- Open the `.dmg`, drag `Tang!.app` to Applications, then run.

### Android app (APK)

- Download the latest APK from GitHub Releases: `https://github.com/yangtaengg/Tang/releases/latest`
- Install on your Android device and allow Notification Access when prompted.

## Automated Build and Release

- On every push to `main`, GitHub Actions builds both apps and publishes an auto prerelease.
- Workflow file: `.github/workflows/build-release.yml`
- Auto release tag format: `auto-<UTC timestamp>-<short-sha>`
- Uploaded assets:
  - `app-debug.apk`
  - `Tang-macOS.dmg`

Notes:

- The current DMG is unsigned/not notarized (suitable for testing and internal distribution).
- The current APK is a debug build.
- For production distribution, switch Android job to signed release APK/AAB and add Apple code-sign + notarization steps for macOS.

## Quick Start

1. Install and run `Tang!` on macOS.
2. Open `Pair device` in the menu bar app.
3. Install and open the Android app from `android-app`.
4. Enable notification access in Android settings.
5. Scan the QR and keep both devices on the same Wi-Fi.

Detailed docs:

- `macos-menubar/README.md`
- `android-app/README.md`

## Policy Intent

This project avoids SMS/call sensitive permissions:

- No `READ_SMS`
- No `RECEIVE_SMS`
- No `READ_CALL_LOG`

Android data source is notification access (`NotificationListenerService`) only.

## Support

- Buy me a coffee: `https://www.buymeacoffee.com/yangtaengg`
