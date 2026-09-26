# Contributing to Eccles BlueChat

Thanks for considering it — this project is actively in development and
contributions of every size are welcome, from typo fixes to a full
cryptography review.

## Where help is most needed right now

1. **Device testing.** The current E2EE pass has not been run on real
   hardware yet. Running the connect → chat → call → video flow on a real
   device pair — and specifically the trust-changed-warning path (reinstall
   one side, confirm the fingerprint-mismatch dialog fires) — is the single
   most valuable thing a contributor can do right now.
2. **Cryptography review.** `Handshake`, `EccSession`, `Hkdf`, and
   `IdentityKeyManager` implement a hand-rolled X3DH-style protocol. If you
   have protocol or applied-crypto review experience, an independent look
   at these before real conversations rely on them would be extremely
   valuable.
3. **Automated tests.** Known-answer tests for the key derivation, replay-
   rejection tests, and wire-protocol framing tests don't exist yet.
4. **General bug reports and fixes.** See open issues, or file a new one.

## Getting set up

```bash
git clone <this-repo-url>
cd "Eccles Bluechat E2EE"
./gradlew assembleDebug
./gradlew test
```

Two physical Android devices are needed to meaningfully exercise Bluetooth
behavior — an emulator will not surface most real bugs in this app.

## Making a change

1. Open an issue first for anything non-trivial (new features, protocol
   changes, anything touching `crypto/`) so the approach can be discussed
   before you invest time in it.
2. Fork the repo and create a branch off `main` for your change.
3. Keep pull requests focused — one logical change per PR is much easier
   to review than several bundled together.
4. Add or update tests for anything you change in `crypto/`, `data/`, or
   `stream/`.
5. Make sure `./gradlew test` passes before opening the PR. If you have a
   device available, run `./gradlew connectedAndroidTest` too and mention
   in the PR description what you tested it against.
6. Describe *what* changed and *why* in the PR description — for anything
   touching the wire protocol or encryption, explain the security
   implication, not just the mechanics of the change.

## Code style

- Match the existing style of the file you're editing rather than
  introducing a new one.
- Per an earlier project decision, source files in this codebase are kept
  free of inline comments — if a change needs explaining, put that
  explanation in the PR description or `CHANGES.md`, not in the code.
- Favor small, explicit methods over cleverness, especially anywhere
  touching cryptography or session state.

## Reporting security issues

If you find a vulnerability in the encryption, session handling, or
trust-store logic, please avoid filing it as a public issue until it's had
a chance to be assessed privately. Open an issue asking for a private
contact, or reach out to a maintainer directly if you have a way to.

## Code of conduct

Be respectful and constructive. Disagreements about approach are normal and
welcome — personal attacks are not.
