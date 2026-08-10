# Google Play SMS permission review

The review video demonstrates Tang's core cross-device SMS flow:

1. Tang discloses that Android SMS sending is optional and used for Mac-triggered replies.
2. Android pairs directly with the macOS app by QR code or a 12-character code on the same LAN.
3. An incoming Android SMS is relayed to the paired Mac.
4. The user enters a reply on the Mac, and Android sends that requested SMS using `SEND_SMS`.

No account or cloud relay server is required. The demo uses an Android emulator and the reserved `555-123-4567` test number; no carrier SMS was sent.
