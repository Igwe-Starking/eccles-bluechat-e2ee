# Contributing

Thanks for helping. Bluechat is small and focused, so a few rules keep it that way.

## Ground rules

1. **No network permissions.** Bluechat is offline by design. A pull request that adds `INTERNET` or `ACCESS_NETWORK_STATE` will fail CI and will not be merged.
2. **Crypto changes need tests and an explanation.** Anything under `crypto/` must come with unit tests and a clear description of the reasoning.
3. **No personal data in the repo.** No private chat screenshots, real contact names or photos, keystores, or `local.properties`.
4. **Keep scripts in bash.** Executables live in `scripts/` and CI calls them.

## Workflow

```bash
bash scripts/doctor.sh      # check your setup
bash scripts/ci.sh --fast   # quick guards, no Gradle
bash scripts/ci.sh          # full: guards, unit tests, debug build
```

1. Fork, then branch from `main`.
2. Make your change, and add or update tests.
3. Run `bash scripts/ci.sh`.
4. Open a pull request using the template.

## Testing on devices

Bluetooth behaviour varies a lot between phones. If you test on real hardware, include both models and Android versions in your report.

## Funding

Contributions are welcome whether or not you give money. If you want to back the work, see the [support page](docs/SUPPORT_THE_PROJECT.md).
