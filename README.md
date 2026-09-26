# Eccles BlueChat

**Serverless, end-to-end encrypted chat, voice and video calling over classic
Bluetooth — no internet connection, no server, no account.**

> 🚧 **Status: active development.** The E2EE hardening pass (wire protocol
> v3, at-rest encryption, trust-on-first-use) is a large, single-pass review
> that has **not yet been compiled or run on a device**. Treat this as a
> pre-release codebase, not a finished product — see
> [What's left before this ships](#whats-left-before-this-ships) below.
> **Contributors are very welcome**, especially for device testing and a
> cryptography review — see [Contributing](#contributing).

---

## What it is

Eccles BlueChat pairs two Android devices directly over Bluetooth (no Wi-Fi,
no mobile data, no backend server) and lets them exchange text, images,
voice notes and video, plus place voice/video calls, entirely peer-to-peer.
Because there's no server in the loop, there's nothing to breach, subpoena,
or take offline — your conversation exists only on the two devices having it.

## Security model

| Layer | Mechanism |
|---|---|
| Key exchange | Authenticated ECDH handshake (`Handshake`) combining a long-term per-device identity key with fresh per-session ephemeral keys — X3DH-style triple-ECDH over P-256, giving forward secrecy plus implicit authentication |
| Session traffic | AES-256-GCM, independent keys per direction, strictly increasing nonce counters (replay rejection). GCM's own auth tag is the integrity/tamper check |
| Identity keys | Generated once per install; private key wrapped with an AndroidKeystore-backed AES-GCM key and stored in `getNoBackupFilesDir()` (excluded from backup/restore) |
| Peer trust | Trust-on-first-use (`TrustStore`) pins each contact's identity fingerprint on first connection. A later fingerprint change blocks the connection and surfaces an explicit warning — the same MITM-detection model Signal/SSH use. TOFU cannot protect the very first contact with a peer; only out-of-band fingerprint verification (in person, by phone) can do that, which is why the account screen shows your own device's fingerprint |
| At-rest data | Message text (`ChatBase`) and all chat media — photos, voice notes, video (`EcclesStorage`) — encrypted on disk with separate AndroidKeystore-backed AES-256-GCM keys. Media is decrypted only into a cache-only, non-backed-up temp copy when displayed/played, and that cache is cleared on app start |

Full detail on this pass — including every fix made along the way — is in
[`CHANGES.md`](CHANGES.md).

**This is hand-rolled protocol code.** It has not had an independent
cryptography review. Don't rely on it to protect anything where the stakes
of compromise are high until it has had one — see
[What's left before this ships](#whats-left-before-this-ships).

## Features

- Peer-to-peer text, image, voice-note and video-file chat over Bluetooth
- Voice and video calling, no carrier or internet involved
- No ads, no in-app purchases, no `INTERNET` permission — nothing in the
  app talks to the network at all
- Works fully offline by design, not as a fallback mode

## Requirements

- Android Studio (a recent stable release)
- Android SDK: `minSdk` 26, `compileSdk`/`targetSdk` 34
- Two physical Android devices with Bluetooth for any real testing —
  Bluetooth behavior is not meaningfully testable on an emulator

## Building

```bash
git clone <this-repo-url>
cd "Eccles Bluechat E2EE"
./gradlew assembleDebug
```

Or open the project directory in Android Studio and run it from there.

### Tests

```bash
# Unit tests (local JVM, no device needed)
./gradlew test

# Instrumented tests (need a connected device/emulator — cover AndroidKeystore,
# real SQLite, and Bluetooth-adjacent behavior Robolectric can't faithfully simulate)
./gradlew connectedAndroidTest
```

## Project structure

```
app/src/main/java/starking/eccles/
├── bluechat/         # Activities, services, UI, preferences (the app itself)
├── crypto/           # Handshake, session/key management, trust store
├── data/             # Persistence (ChatBase, encrypted storage)
├── stream/           # Wire protocol read/write (Reader/Writer)
├── receivers/        # Bluetooth state, availability
└── util/             # Shared helpers
```

## What's left before this ships

This pass was a large, single-pass source review — it has **not** been
compiled or run. Before it's trustworthy for real use:

- [ ] Build and run the full connect → chat → call → video flow on a real
      device pair, including the trust-changed-warning path (reinstall one
      side and confirm the fingerprint-mismatch dialog fires correctly)
- [ ] Add automated tests for `Handshake`/`EccSession` — known-answer tests
      for key derivation, replay-rejection tests — and for wire protocol
      framing; none existed before this pass
- [ ] Encrypt the contact profile-icon store in `ClassicCompat` (currently
      plaintext in `SharedPreferences`) for full at-rest coverage
- [ ] An independent second-pass review of the cryptography by someone
      with protocol/crypto review experience

## Contributing

Contributions of all sizes are welcome — device testing, bug reports, code
review, and especially cryptography expertise, given the item above. Please
see [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a pull request.

## License

See [`LICENSE`](LICENSE).
