# macOS Menu Bar App

SwiftUI menu bar app that hosts a local WebSocket server, shows pairing QR, stores pairing token in Keychain, and displays incoming SMS notifications from the Android relay client.

## Features

- Menu bar dropdown with recent 10 messages
- Click any message row to copy sender + body
- Local macOS notification for each new message
- Pairing screen with QR payload and token regeneration
- challenge-response authentication and encrypted protocol v2 sessions

## Build and Run

Prerequisites:

- Xcode 15+
- macOS 13+

Run with SwiftPM:

```bash
cd macos-menubar
swift build
swift run SmsRelayMenuBar
```

Or open in Xcode as a Swift package and run the executable target.

## Pairing

1. Open menu bar app.
2. Click `Pair device`.
3. Scan the QR from Android, or enter the displayed setup code.

QR payload includes:

- `url` (`ws://<mac-ip>:8765/ws`)
- `pairingToken` (one-time random 32-byte base64 secret)
- `expiresAtMs` (10-minute pairing window)
- `deviceName`

The long-term token is stored in macOS Keychain and is delivered to Android only inside the encrypted pairing session.

## Security

- HMAC-SHA256 challenge-response authentication with constant-time proof comparison and failure rate limiting
- directional HKDF-SHA256 session keys and AES-256-GCM payload encryption
- exact receive sequence enforcement, replay rejection, bounded inputs, and a 256 KiB frame limit
- current local transport is `ws`; application payloads are encrypted, but network metadata remains visible

## Known Limitations

- A signed public build requires Developer ID credentials and Apple notarization; the tag workflow enforces both
- Message delivery depends on Android notification availability
- If Android app/process is heavily battery-restricted, relay may be delayed
