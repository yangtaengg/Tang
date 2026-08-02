# Android Relay App

Android app that listens for SMS notification posts from supported messaging apps and relays them to the paired macOS app over WebSocket.

## Policy-Safe Permission Model

Used:

- `BIND_NOTIFICATION_LISTENER_SERVICE` (service declaration)
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `CAMERA` (QR pairing)
- `POST_NOTIFICATIONS` and foreground-service permissions (visible relay status)
- `SEND_SMS` (optional, user-initiated direct `reply_sms` sending)

Not used:

- `READ_SMS`
- `RECEIVE_SMS`
- `READ_CALL_LOG`
- `READ_PHONE_STATE`

## Supported Source Apps

- `com.samsung.android.messaging`
- `com.google.android.apps.messaging`

## Data Extraction

Priority:

1. `Notification.EXTRA_MESSAGES` (MessagingStyle)
2. Fallback `EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_BIG_TEXT`

Only new message notifications are relayed after dedupe.

## Build and Run

Prerequisites:

- Current Android Studio
- Android SDK 36
- JDK 17

Steps:

1. Open `android-app` in Android Studio.
2. Sync Gradle.
3. Run app on device (Galaxy recommended for Samsung Messages testing).

## Setup Flow

1. Open app and tap `Open Notification Access Settings`.
2. Enable notification access for this app.
3. Tap `Pair via QR Scan` and scan the QR from macOS app.
4. Keep phone and Mac on same Wi-Fi.

The QR/setup-code credential expires after 10 minutes. After successful pairing, the long-term token is delivered through the encrypted session and persists until pairing is cleared or the Mac token is regenerated.

## Transport Protocol

Protocol v2 uses `auth.hello`, a random server challenge, and an HMAC-SHA256 proof. It derives directional keys and sends all later payloads inside AES-256-GCM `secure` frames. See `../shared/protocol.md` for the complete contract.

An example decrypted event is:

```json
{"type":"sms.notification","id":"uuid","timestamp":1760000000000,"from":"Alice","body":"Hello","sourcePackage":"com.google.android.apps.messaging"}
```

Server can request quick reply when the source notification supports inline reply:

```json
{"type":"sms.reply","replyKey":"<notification-key>","body":"On my way"}
```

Server can request direct SMS send (requires `SEND_SMS` runtime permission):

```json
{"type":"reply_sms","sourcePackage":"com.google.android.apps.messaging","conversation_id":"Alice","to":"+821012345678","body":"Running late","client_msg_id":"<uuid>","timestamp":1760000000000}
```

Client returns direct SMS send result:

```json
{"type":"reply_sms.result","client_msg_id":"<uuid>","success":true}
```

## Reliability

- Exponential-backoff reconnect (up to 30s delay)
- Auth-ack gate before event flush
- Local dedupe using package + conversation + body + rounded timestamp
- Battery optimization guidance available from main screen (not forced)

## Known Limitations

- Some notifications (for example OTP/privacy-hidden content) may be partially masked by source app or Android system
- If notification access is disabled, no relay occurs
- Aggressive device sleep modes can delay reconnect or event forwarding
- A real Samsung/Google Messages device test is still required before each store release because notification payloads vary by app and OS version.
