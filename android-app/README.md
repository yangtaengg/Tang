# Android Relay App

Android app that listens for SMS notification posts from supported messaging apps and relays them to the paired macOS app over WebSocket.

## Policy-Safe Permission Model

Used:

- `BIND_NOTIFICATION_LISTENER_SERVICE` (service declaration)
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `CAMERA` (QR pairing)
- `SEND_SMS` (for direct `reply_sms` sending)

Not used:

- `READ_SMS`
- `RECEIVE_SMS`
- `READ_CALL_LOG`

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

- Android Studio Iguana+ (or newer)
- Android SDK 35
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

Pairing token is persistent (non-expiring) unless you manually clear pairing or regenerate token on macOS.

## Transport Protocol

Client sends auth first:

```json
{"type":"auth","token":"...","device":"SM-S918N","appVersion":"0.1.0"}
```

After `auth.ok`, client sends events:

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
- `EncryptedSharedPreferences` is used per requirement; plan migration to DataStore/Tink in production hardening
