# Tang! 개인정보 처리방침

시행일: 2026-08-02

Tang!(이하 "앱")은 Android 알림 기반 메시지/전화 알림을 사용자의 macOS 기기로 릴레이하기 위해 최소한의 정보만 처리합니다.

## 1. 수집/처리하는 정보

앱은 아래 정보를 처리할 수 있습니다.

- 알림 접근 권한을 통해 읽은 메시지 알림 정보
  - 발신자 표시명, 발신자 번호(가능한 경우), 메시지 본문, 알림 시각, 앱 패키지명
- 지원되는 전화 앱의 알림 정보
  - 수신 전화 여부, 발신 번호/이름(알림에 표시되는 범위)
- 사용자가 직접 입력한 페어링 정보
  - macOS 연결 주소(URL), 페어링 토큰/코드

## 2. 정보 처리 목적

- 사용자가 페어링한 macOS 앱에 메시지/수신 전화 알림을 전달하기 위해
- 사용자가 요청한 기능 수행을 위해
  - macOS에서 답장 요청 시 Android에서 SMS 전송
  - macOS에서 수신 전화 알림의 "끊기" 동작 요청 시 해당 알림 액션 수행(지원 기기/OS 한정)

## 3. 처리 방식 및 전송 범위

- 앱은 사용자가 QR 또는 설정 코드로 직접 페어링한 macOS 기기와 로컬 네트워크 경로로만 데이터를 전송합니다.
- WebSocket 연결 자체가 `ws://`인 경우에도 인증 후의 메시지 내용은 세션별 키를 사용하는 AES-256-GCM으로 암호화되고 무결성이 검증됩니다.
- 중앙 서버(클라우드 백엔드)로 데이터를 업로드하지 않습니다.
- 제3자 광고 SDK/분석 SDK를 사용하지 않습니다.

## 4. 권한 사용 안내

앱은 다음 권한을 사용할 수 있습니다.

- `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` (알림 접근 서비스): 메시지/전화 알림 수신
- `android.permission.POST_NOTIFICATIONS`: 앱 상태 알림 표시
- `android.permission.SEND_SMS`: 사용자 요청 시 SMS 전송
- `android.permission.CAMERA`: QR 페어링 스캔
- `android.permission.INTERNET`, `android.permission.ACCESS_NETWORK_STATE`: macOS와 네트워크 연결

각 권한은 기능 제공 목적에만 사용됩니다.

## 5. 보관 및 삭제

- 알림 데이터는 기능 수행을 위한 일시 처리 후 즉시 소비됩니다.
- 페어링 정보(토큰/URL)는 Android Keystore의 키로 AES-GCM 암호화하여 기기 내부에 저장됩니다. macOS의 장기 페어링 토큰은 Keychain에 저장됩니다.
- Android는 메시지 내용을 디스크에 저장하지 않습니다. macOS 앱은 실행 중 최근 메시지 최대 10개를 메모리에만 보관하며 앱 종료 시 사라집니다.
- 사용자는 앱의 "Clear Pairing" 기능 또는 앱 삭제를 통해 저장 정보를 삭제할 수 있습니다.

## 6. 제3자 제공

앱은 사용자의 개인정보를 제3자에게 판매/임대/제공하지 않습니다.
법령에 따른 의무가 있는 경우를 제외하고 외부 제공을 하지 않습니다.

## 7. 아동의 개인정보

앱은 만 14세 미만 아동을 대상으로 설계되지 않았습니다.

## 8. 이용자 권리

사용자는 언제든지 다음을 할 수 있습니다.

- 알림 접근 권한/통화 권한/SMS 권한 철회
- 페어링 정보 삭제
- 앱 삭제

권한 철회 시 일부 기능은 동작하지 않을 수 있습니다.

## 9. 보안

앱은 Android Keystore/macOS Keychain, 일회성 페어링 자격 증명, challenge-response 인증, 세션 키 분리, AES-256-GCM 암호화, 재전송 방지를 사용해 정보를 보호합니다.
다만 네트워크/기기 환경 특성상 100% 보안을 보장할 수는 없습니다.

## 10. 개인정보 처리방침 변경

본 방침은 필요 시 변경될 수 있으며, 변경 시 저장소 문서 또는 앱 배포 페이지를 통해 고지합니다.

## 11. 문의

개인정보 관련 문의 또는 삭제 요청은 [Tang GitHub Issues](https://github.com/yangtaengg/Tang/issues)를 이용해 주세요. 공개 이슈에 전화번호, 메시지 내용 등 개인정보를 포함하지 마세요.

---

# Tang! Privacy Policy (English)

Effective date: August 2, 2026

Tang! processes only the data needed to relay supported Android message and incoming-call notifications to a Mac selected by the user.

## Data processed and purpose

- Notification sender, available phone number, message body, notification time, and source app package are processed to show the notification on the paired Mac.
- Incoming-call sender information visible in a supported call notification is processed to show a call alert on the paired Mac.
- The pairing URL, device name, and credential are processed to authenticate the paired devices.
- An SMS destination and body are processed only when the user initiates a reply from the paired Mac and has explicitly granted the optional `SEND_SMS` permission.

## Transfer, storage, and sharing

- Data is sent only over the local network to the Mac paired by the user. Tang! has no cloud backend and includes no advertising or analytics SDK.
- After authentication, application payloads are encrypted and integrity-protected with per-session AES-256-GCM keys, including when the local WebSocket URL uses `ws://`.
- Android does not persist message content. The Mac keeps at most 10 recent messages in memory while the app is running; they disappear when the app exits.
- Pairing data is encrypted with a key held by Android Keystore. The Mac long-term pairing token is stored in Keychain.
- Tang! does not sell, rent, or share personal data with third parties, except where disclosure is required by law.

## Permissions

- Notification access reads supported message and call notifications for relay and notification-action replies.
- Camera access is optional and used only to scan a pairing QR code.
- `SEND_SMS` is optional and used only for a reply initiated from the paired Mac.
- Notification, network, and foreground-service permissions support the visible background relay connection.

Permissions can be revoked in Android settings. Pairing data can be removed with Disconnect/Clear Pairing or by uninstalling the app.

## Children, security, and changes

Tang! is not directed to children under 14. It uses Android Keystore, macOS Keychain, time-limited one-time pairing credentials, challenge-response authentication, directional session keys, AES-256-GCM, and replay rejection. No system can guarantee absolute security. Material policy changes will be posted in this repository and on the distribution page.

## Contact

For privacy questions or deletion requests, use [Tang GitHub Issues](https://github.com/yangtaengg/Tang/issues). Do not post phone numbers, message contents, or other personal information in a public issue.
