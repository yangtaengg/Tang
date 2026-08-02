# Tang Secure Relay Protocol v2

Tang uses a local WebSocket for transport and encrypts every application payload after authentication. Protocol v1 plaintext authentication and payloads are rejected.

## Pairing credentials

Opening the macOS pairing window creates two credentials that expire after 10 minutes and are invalidated after the first successful pairing:

- a random 32-byte QR secret;
- a 12-character base32 setup code (60 bits) for manual entry.

The QR contains UTF-8 JSON:

```json
{
  "version": 2,
  "url": "ws://192.168.0.10:8765/ws",
  "pairingToken": "<ephemeral-base64-secret>",
  "expiresAtMs": 1785640000000,
  "deviceName": "MacBook Pro"
}
```

Android accepts pairing URLs only for loopback, link-local, or private-network hosts. After successful authentication with a one-time credential, the Mac sends its random long-term token inside the encrypted channel. Android stores it using an Android Keystore-backed AES-GCM key; macOS stores it in Keychain.

## Authentication handshake

All handshake fields are JSON text frames. Nonces are 32 random bytes encoded with standard Base64.

Client to server:

```json
{"type":"auth.hello","version":2,"clientNonce":"<base64>","device":"SM-S918N","appVersion":"1.0.0"}
```

Server to client:

```json
{"type":"auth.challenge","version":2,"challenge":"<base64>"}
```

Client to server:

```json
{"type":"auth.proof","proof":"<base64-hmac-sha256>"}
```

The proof is `HMAC-SHA256(secret, authInput)`, where `authInput` is the UTF-8 encoding of these newline-separated values:

```text
Tang/auth/v2
<clientNonceBase64>
<challengeBase64>
<base64(UTF-8 device)>
<base64(UTF-8 appVersion)>
```

The server compares proofs in constant time and rate-limits failed authentication. On success:

```json
{"type":"auth.ok","version":2,"secure":true}
```

On failure the server sends `auth.fail` and closes the client.

## Session keys and secure frames

Two 32-byte keys are derived with HKDF-SHA256. The HKDF salt is `clientNonce || challenge`; input key material is the UTF-8 credential. The info values are:

- `Tang/client-to-server/v2`
- `Tang/server-to-client/v2`

Every post-authentication message is UTF-8 JSON encrypted with AES-256-GCM. Each direction has its own sequence beginning at 1 and its own key. Frames use a random 12-byte nonce and the UTF-8 AAD `Tang/frame/v2/<sequence>`:

```json
{
  "type": "secure",
  "seq": 1,
  "nonce": "<base64-12-byte-nonce>",
  "ciphertext": "<base64-ciphertext-and-16-byte-tag>"
}
```

Receivers require the next exact sequence number. Invalid authentication tags, skipped/replayed sequences, malformed frames, and frames over 256 KiB terminate the connection.

For a first-time pairing, the first encrypted server payload contains the long-term credential:

```json
{"type":"pairing.complete","token":"<long-term-base64-token>"}
```

## Encrypted application payloads

Android to Mac:

```json
{"type":"sms.notification","id":"<uuid>","timestamp":1785640000000,"from":"Alice","body":"Hello","sourcePackage":"com.google.android.apps.messaging","conversationKey":"Alice","replyKey":"<optional-notification-key>"}
```

```json
{"type":"call.incoming","id":"<uuid>","timestamp":1785640000000,"from":"Alice","name":"Alice Smith"}
```

```json
{"type":"sms.reply.result","replyKey":"<notification-key>","success":true}
```

```json
{"type":"reply_sms.result","client_msg_id":"<uuid>","success":false,"reason":"send_sms permission required"}
```

Mac to Android:

```json
{"type":"sms.reply","replyKey":"<notification-key>","sourcePackage":"com.google.android.apps.messaging","conversationKey":"Alice","body":"On my way"}
```

```json
{"type":"reply_sms","sourcePackage":"com.google.android.apps.messaging","conversation_id":"Alice","to":"+821012345678","body":"Running late","client_msg_id":"<uuid>","timestamp":1785640000000}
```

```json
{"type":"call.hangup"}
```

Heartbeat payloads are `ping` and `pong`. Message event IDs are deduplicated on the Mac. Direct SMS commands are idempotent by `client_msg_id`, with recent results cached on Android.

## Security boundary

Protocol v2 protects application content and credentials from passive local-network observers and detects modification or replay. A `ws://` connection still exposes IP addresses, port, frame timing, and approximate sizes. Device compromise, malicious notification content, and actions the user authorizes on the paired endpoints remain outside the transport protocol's protection.
