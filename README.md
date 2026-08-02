# Tang!

Tang! relays supported Android message and incoming-call notifications to a paired macOS menu bar app over the local network. It has no cloud backend, advertising SDK, or analytics SDK.

## Components

- `macos-menubar`: SwiftUI menu bar app, local WebSocket server, Keychain token storage, message/call notifications, and replies.
- `android-app`: Kotlin Android app, `NotificationListenerService`, Android Keystore-backed pairing storage, and foreground relay client.
- `shared/protocol.md`: version 2 authentication, key derivation, encrypted-frame, and payload contract.
- `PRIVACY_POLICY.md`: Korean and English privacy policy used by the Android app and store listing.

## Security model

- Pairing QR secrets and 12-character setup codes expire after 10 minutes and are invalidated after successful pairing.
- Authentication uses a random challenge and HMAC-SHA256 proof; credentials are never sent as plaintext WebSocket messages.
- Directional session keys are derived with HKDF-SHA256. Every application payload is protected with AES-256-GCM, an exact sequence number, and replay rejection.
- Android stores pairing data encrypted by an Android Keystore key. macOS stores its long-term token in Keychain.
- The server rate-limits authentication failures, rejects v1 clients, and caps frames at 256 KiB.

The connection currently uses local `ws://` transport, so endpoint addresses, timing, and approximate frame sizes remain visible even though application payloads are encrypted. See `shared/protocol.md` for the exact boundary.

## Build and verification

Android requires JDK 17 and Android SDK 36:

```bash
cd android-app
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:bundleRelease
```

macOS requires Xcode/Swift 5.10 and macOS 13 or newer:

```bash
cd macos-menubar
swift test
swift build -c release --product SmsRelayMenuBar
```

Release builds use R8/resource shrinking. A local build is unsigned unless the four `ANDROID_KEY*`/`ANDROID_KEYSTORE*` environment variables documented in the workflow are supplied.

## Release pipeline

[`.github/workflows/build-release.yml`](.github/workflows/build-release.yml) runs tests, Android lint, release APK/AAB builds, and a macOS release build on pull requests and pushes. A tag matching `v*` additionally:

1. signs Android artifacts with the Play upload key;
2. uploads the AAB to the Google Play `beta` (open testing) track;
3. signs the macOS app with Developer ID, notarizes it, and staples the ticket;
4. publishes the signed APK/AAB and notarized macOS ZIP as a GitHub Release.

Required GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`
- `MACOS_CERTIFICATE_P12_BASE64`
- `MACOS_CERTIFICATE_PASSWORD`
- `APPLE_SIGNING_IDENTITY`
- `APPLE_ID`
- `APPLE_TEAM_ID`
- `APPLE_APP_SPECIFIC_PASSWORD`

The Google service account must have permission for this app in Play Console. The Play listing still needs an accurate Data safety form, privacy-policy URL, `SEND_SMS` permission declaration/approval, tester eligibility, and completed review. Tagging does not bypass those external gates.

## Setup

1. Install and open Tang! on macOS, then choose **Pair device**.
2. Install and open Tang! on Android.
3. Review the in-app disclosure and enable notification access.
4. Scan the Mac QR or enter its 12-character setup code while both devices are on the same local network.
5. Optionally enable direct SMS sending or battery-optimization guidance from the connected screen.

Tang! does not request `READ_SMS`, `RECEIVE_SMS`, `READ_CALL_LOG`, or `READ_PHONE_STATE`. Direct `SEND_SMS` is optional; notification-action quick replies can work without it when the source app exposes a reply action.

## Support and privacy

- Privacy policy: [PRIVACY_POLICY.md](PRIVACY_POLICY.md)
- Issues and privacy contact: [GitHub Issues](https://github.com/yangtaengg/Tang/issues)
- Support the project: [Buy me a coffee](https://www.buymeacoffee.com/yangtaengg)
