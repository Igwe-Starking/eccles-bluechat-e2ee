## Eccles Bluechat 3.1.0: end-to-end encryption

Private chat, voice calls and video calls over Bluetooth. No internet, no accounts, no ads.

### Highlights
- 🔒 **End-to-end encryption** on every connection (ECDH P-256, HKDF-SHA256, AES-256-GCM), with forward secrecy between connections
- 🛡️ **Key-change warnings** that stop a connection if a contact's identity changes
- 🗄️ **Encrypted storage** for messages and media, with automatic migration
- 👆 **App lock** (fingerprint or PIN) and screenshot blocking while it is on
- 🚫 **No `INTERNET` permission.** Ads and billing code are gone.
- ⚡ Fixes for a CPU-spinning receive loop, an Android 14 crash and a quick-reply crash

### Install
1. Download `EcclesBluechat-3.1.0.apk` below.
2. Verify it (optional but recommended):
   ```bash
   git clone https://github.com/Igwe-Starking/eccles-bluechat-e2ee.git
   bash eccles-bluechat-e2ee/scripts/verify-apk.sh EcclesBluechat-3.1.0.apk <sha256 from the .sha256 file>
   ```
3. Open the APK, allow **Install unknown apps** if asked, and grant the requested permissions.

**Requires** Android 8.0 or newer. **Both phones** must run 3.1.0 or newer.

### Heads up
The encryption is custom and **not independently audited**. See the [security model](https://github.com/Igwe-Starking/eccles-bluechat-e2ee/blob/main/docs/SECURITY_MODEL.md). Older versions cannot connect to 3.1.0 by design.

### Support
Bluechat is built by one student developer on a struggling Chromebook. If it is useful to you, help fund a Google Play developer account and a proper laptop: **https://paystack.shop/pay/support-bluechat-t1**

Full changes: [CHANGELOG.md](https://github.com/Igwe-Starking/eccles-bluechat-e2ee/blob/main/CHANGELOG.md)
