# macOS Menu Bar App

SwiftUI menu bar app that hosts a local WebSocket server, shows pairing QR, stores pairing token in Keychain, and displays incoming SMS notifications from the Android relay client.

## Features

- Menu bar dropdown with recent 50 messages
- Click any message row to copy sender + body
- Local macOS notification for each new message
- Pairing screen with QR payload and token regeneration
- Token-authenticated WebSocket server (`auth` -> `auth.ok`)

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
3. Set Mac local IP if needed.
4. Scan QR from Android app.

QR payload includes:

- `url` (`ws://<mac-ip>:8765/ws`)
- `pairingToken` (random 32-byte base64)
- `expiresAtMs` (set to non-expiring for persistent pairing)
- `deviceName`

Token is stored in macOS Keychain.

## Security

- Minimum implemented: local network + token auth
- Current transport: `ws`
- Server/client protocol designed to allow future `wss` upgrade with minimal surface changes

## Known Limitations

- `wss` certificate provisioning is not fully wired in this MVP
- Message delivery depends on Android notification availability
- If Android app/process is heavily battery-restricted, relay may be delayed
