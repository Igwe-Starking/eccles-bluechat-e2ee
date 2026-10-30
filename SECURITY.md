# Security policy

## Reporting a vulnerability

Please **do not** open a public issue for a security problem.

Use GitHub's private reporting: **Security tab > Report a vulnerability**
(https://github.com/Igwe-Starking/eccles-bluechat-e2ee/security/advisories/new).

Include what you found, how to reproduce it, which version, and the impact you think it has. I will acknowledge a report as soon as I can. This is a one-person project, so please be patient, and I will credit you in the fix unless you prefer otherwise.

## Scope

In scope: the handshake, session encryption, framing, trust store, key storage, at-rest encryption, app lock, and anything that could expose message content, media or keys.

Out of scope: attacks that need a rooted or already-compromised phone, and Bluetooth metadata that is visible by nature (see the [security model](docs/SECURITY_MODEL.md#known-limitations-and-review-items)).

## Cryptographic review wanted

The protocol is custom and **has not been independently audited**. If you have cryptography experience, a review of the items in the security model would be a real contribution, and I will gladly document your findings.

## Supported versions

Only the latest release receives fixes.
