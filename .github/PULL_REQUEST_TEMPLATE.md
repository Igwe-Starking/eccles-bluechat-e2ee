## What this changes

Brief description of the change and why it's needed.

## Testing

- [ ] `./gradlew test` passes
- [ ] `./gradlew connectedAndroidTest` passes (if you have a device — note
      which device/Android version)
- [ ] Manually exercised the affected flow on real hardware (describe below)

## Security-relevant changes

If this touches `crypto/`, `data/` (encrypted storage), or `stream/` (wire
protocol), explain the security implication of the change, not just the
mechanics.

## Checklist

- [ ] I opened an issue to discuss this first (required for anything
      non-trivial or touching `crypto/`)
- [ ] I added/updated tests for the behavior I changed
- [ ] I did not add inline code comments (per project convention — see
      `CONTRIBUTING.md`)
