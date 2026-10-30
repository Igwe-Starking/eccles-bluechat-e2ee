# Changelog

## 3.1.0 — End-to-end encryption and hardening

### Added
- **End-to-end encryption for every connection.** Authenticated ECDH handshake (P-256, identity plus ephemeral keys, triple-DH) with HKDF-SHA256, then AES-256-GCM with per-direction keys and replay rejection. Forward secrecy between connections. Both devices must run 3.1.0 or newer.
- **Trust on first use.** Contact fingerprints are pinned on first contact. A changed fingerprint blocks the connection and shows a warning with the new fingerprint. Your own fingerprint is on the account screen.
- **Encrypted storage.** Messages and all chat media are encrypted at rest with Android Keystore keys. Existing chats are migrated automatically.
- **App lock** with fingerprint or device PIN, plus screenshot and screen-recording blocking while it is on.
- "Chats and calls are end-to-end encrypted" banner in chats.
- Bash tooling: `scripts/ci.sh`, build, test, sign, release and verification scripts, and GitHub Actions that run them.
- A CI guard that fails the build if the `INTERNET` permission is ever added.

### Removed
- Ads (AdMob) and the Amazon billing integration. Video calling is no longer behind a paywall.
- The `INTERNET` and `ACCESS_NETWORK_STATE` permissions. The app cannot reach the network.

### Fixed
- Receive loop busy-spinning a CPU core for the whole of every voice or video call.
- Handshake and session management: per-peer locking, a 15 second handshake timeout, and a leaked-session bug on disconnect.
- Crash on Android 14+ caused by receiver registration without an exported flag.
- Notification quick-reply crashing when the app was not in the foreground, and reply text being dropped on some keyboards.
- Notification channel creation skipped on Android 8.0 (API 26).
- "Disconnect other devices while on a call" preference did nothing; it now works.
- Possible crash when sending media if local storage or encryption failed; the user now sees an error.
- SQL identifier escaping in the legacy-table migration.
- Several compile errors in the original source, and removal of an old unencrypted call-audio path that bypassed the new encryption.

### Changed
- Minimum Android raised from 4.4 (API 19) to 8.0 (API 26), required for reliable Keystore AES-GCM.
- Target and compile SDK 35. Release builds are minified and resource-shrunk.

### Known limitations
The protocol has not had an independent audit. See [docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md#known-limitations-and-review-items).

The detailed engineering log is kept in [docs/ENGINEERING_NOTES.md](docs/ENGINEERING_NOTES.md).
