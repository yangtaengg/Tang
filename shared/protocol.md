# SMS Relay Protocol

## Pairing QR Payload

JSON object encoded as UTF-8 text in QR code:

```json
{
  "version": 1,
  "url": "ws://192.168.0.10:8765/ws",
  "pairingToken": "<base64-32-byte-token>",
  "expiresAtMs": 1760000000000,
  "deviceName": "MacBook Pro"
}
```

## WebSocket Messages

Client -> Server

```json
{"type":"auth","token":"<pairingToken>","device":"SM-S918N","appVersion":"0.1.0"}
```

```json
{"type":"sms.notification","id":"<uuid>","timestamp":1760000000000,"from":"Alice","body":"Hello","sourcePackage":"com.google.android.apps.messaging"}
```

Server -> Client

```json
{"type":"auth.ok"}
```

```json
{"type":"auth.fail","reason":"invalid token"}
```

```json
{"type":"pong"}
```

```json
{"type":"sms.reply","replyKey":"<notification-key>","body":"On my way"}
```

```json
{"type":"reply_sms","sourcePackage":"com.google.android.apps.messaging","conversation_id":"Alice","to":"+821012345678","body":"Running late","client_msg_id":"<uuid>","timestamp":1760000000000}
```

Client -> Server (reply acknowledgements)

```json
{"type":"sms.reply.result","replyKey":"<notification-key>","success":true}
```

```json
{"type":"reply_sms.result","client_msg_id":"<uuid>","success":false,"reason":"send_sms permission required"}
```

## Notes

- Authentication is required before `sms.notification` events are accepted.
- `id` is a UUID generated on Android for idempotency.
- Server should ignore duplicate IDs inside a short TTL window.
- `reply_sms` is idempotent by `client_msg_id`; Android caches recent outcomes and returns the cached result for duplicates.
- Pairing token is non-expiring by default in this MVP; keep both devices on the same Wi-Fi for automatic reconnect.
