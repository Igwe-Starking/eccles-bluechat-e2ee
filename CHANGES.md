# Eccles Bluechat — E2EE and hardening pass

## Encryption (new)

- **Wire protocol v3.** Every connection performs an authenticated ECDH handshake
  (`starking.eccles.crypto.Handshake`) combining a long-term per-device identity
  key with fresh per-session ephemeral keys (X3DH-style triple-ECDH over P-256),
  giving forward secrecy plus implicit authentication. Session traffic is
  AES-256-GCM with independent per-direction keys and strictly increasing nonce
  counters (replay rejection). The old CRC32 "integrity" check is gone — GCM's
  authentication tag is the real integrity/tamper check.
- **Identity keys** are generated once per install and the private key is
  wrapped with an AndroidKeystore-backed AES-GCM key, stored in
  `getNoBackupFilesDir()` (excluded from backup/restore).
- **Trust-on-first-use.** `TrustStore` pins each contact's identity fingerprint
  on first connection. If a fingerprint changes later, the connection is
  blocked and the user sees an explicit warning with the new fingerprint,
  with an option to trust it or cancel. This is the same MITM-detection model
  Signal/SSH use — it cannot protect the very first contact with a peer, only
  verifying the fingerprint out-of-band (in person, by phone) can do that,
  which is why the account screen now shows your own device's fingerprint.
- **At-rest encryption.** Message text (`ChatBase`) and all chat media —
  photos, voice notes, video (`EcclesStorage`) — are encrypted on disk with
  separate AndroidKeystore-backed AES-256-GCM keys. Media is transparently
  decrypted into a cache-only, non-backed-up temp copy only when displayed or
  played, and that cache is cleared on app start.
- Existing installs are migrated automatically: `ChatBase` re-encrypts every
  existing row in place on the DB version bump.

## Removed

- AdMob (banner ads, `MobileAds` init, manifest application ID) and the
  Amazon IAP billing integration — code, manifest entries, layout XML, and
  Gradle dependencies all removed. Video calling is no longer gated behind
  a paywall.
- The `INTERNET` and `ACCESS_NETWORK_STATE` permissions — nothing else in
  the app talks to the network, they existed only for ads/billing.
- All source comments, per request.

## Fixes found during the review

- `EcclesData.java` referenced `COLUMN_ID`, which was never declared —
  this was a compile error in the original source. Added the constant.
- `EcclesData`'s legacy-table migration escaped `'` before interpolating a
  table name into a **double-quoted** SQL identifier — the wrong character
  was escaped, so an embedded `"` could break out of the identifier. Fixed
  to escape `"` as `""`, matching the safer whitelist approach already used
  in `ChatBase`.
- `EcclesStorage.getTone()` referenced `Settings.System` with no import for
  `android.provider.Settings` — another pre-existing compile error.
- `Reader.java`'s receive loop busy-spun at 100% CPU on one core for the
  entire duration of any voice/video call (the `if(app.inCall) continue`
  path had no sleep). Fixed, and removed the two blanket `Thread.sleep()`
  calls around the already-blocking socket read, which were adding latency
  for no benefit.
- Deprecated no-arg `new Handler()` constructions replaced with
  `new Handler(Looper.getMainLooper())` throughout.
- `SessionManager` originally synchronized handshakes on the whole manager
  instance with no I/O timeout: a stalled or hostile peer during the
  handshake would hang that lock forever, blocking every other connection
  attempt on the device. Replaced with per-remote-address locking and a
  hard 15s timeout on the handshake (run on a background executor via
  `Future`, cancelling and closing the socket on timeout).
- `SessionManager.invalidate()` was never called anywhere, so every
  connection's `EccSession` leaked in the session map for the life of the
  process. Wired into `EcclesActivity.onDisconnected()`, the single choke
  point all connection teardown paths already go through.
- Raising `targetSdk` to 34 exposed a real crash: `registerReceiver()`
  without an exported/not-exported flag throws `SecurityException` on
  Android 14+. Fixed both call sites (`EcclesApplication`,
  `SelectActivity`) with `ContextCompat.registerReceiver(...,
  RECEIVER_NOT_EXPORTED)`.
- `BSender` (notification quick-reply) crashed with a `NullPointerException`
  whenever the app wasn't in the foreground, because it called
  `ea.currentActivity.write(...)` unconditionally — the normal case for
  using a quick-reply at all. Rewrote it to fall back to a new headless
  `EcclesApplication.writeHeadless()` path when there is no foreground
  Activity, and moved the blocking work off the main thread with
  `goAsync()` to avoid an ANR. Also fixed a second latent bug in the same
  method: it read the reply via `Bundle.getString()`, but `RemoteInput`
  stores results as `CharSequence`, which `getString()` silently returns
  `null` for on many keyboards — switched to `getCharSequence()`.
- A notification used `setColor(android.R.attr.colorPrimary)`, passing an
  attribute ID where an actual resolved ARGB color is required. Fixed to
  resolve a real color resource.
- `EcclesNotifier`'s notification-channel creation checked
  `SDK_INT > VERSION_CODES.O`, which excludes exactly API 26 — the version
  channels became mandatory on. Since `minSdk` is now 26, this was a live
  bug, not just a theoretical one. Fixed to `>=`.
- Removed `AudioOutputStream`, `AudioInputStream`, `VideoInputStream`,
  `VideoOutputStream`, and `Call.java` after confirming (by grep, across
  the whole tree) that nothing referenced them. They were an older,
  superseded call-audio subsystem that streamed raw bytes directly over a
  plain `DataOutputStream`, completely bypassing `EcclesWriter` and the new
  encryption. Dead code that silently skips encryption is exactly the kind
  of thing worth deleting outright in an E2EE app rather than leaving
  dormant.
- `CallActivity`'s "disconnect other calls" preference (`disconnect_oncall`)
  was checked but did nothing — the code path existed with a completely
  empty `try` block. Implemented it: it now actually disconnects and
  invalidates the sessions for other connected peers when starting a call.
- Removed a dead `if(true){ ... } else { quit(...); }` wrapper in
  `CallActivity.start()` — the `else` branch was unreachable.
- `ChatActivity.sending()` called `.getBytes()` directly on the return value
  of `EcclesStorage.getDir()`, which can return `null` if local media
  storage/encryption fails — this would NPE and silently fail to record the
  sent message. Now checked explicitly, with a user-facing error instead of
  a crash.

## Build

- `minSdk` raised 19 → 26 (required for reliable AndroidKeystore AES-GCM);
  `compileSdk`/`targetSdk` raised to 34. Release builds now enable
  `minifyEnabled`/`shrinkResources`.

## What this pass did not cover

This was a large, single-pass review — it was not compiled or run. Before
shipping, at minimum:

- Build and run through the full connect → chat → call → video flow on a
  real device pair, including the trust-changed-warning path (reinstall one
  side and confirm the fingerprint-mismatch dialog fires correctly).
- Add automated tests around `Handshake`/`EccSession` (known-answer tests
  for the key derivation, replay-rejection tests) and the wire protocol
  framing — none existed before this pass and none were added, since tests
  need to run against a real build to be worth anything.
- Consider encrypting the contact profile-icon store in `ClassicCompat`
  (currently plaintext in SharedPreferences) for full at-rest coverage.
- A second pass by someone else, ideally with cryptography review experience
  — this is hand-rolled protocol code, and hand-rolled crypto is exactly the
  category of code most worth a second set of eyes before it protects real
  conversations.
