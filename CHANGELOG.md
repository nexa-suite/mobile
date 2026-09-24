# Changelog

## 0.1.0 - 2026-09-23

### Added

- Operations Android technical foundation in four modules: `:app`, `:core:auth`, `:core:network`, and `:core:designsystem`.
- Compose application shell with a session-gated Navigation 3 root.
- Native session coordination with memory-only access tokens, Android Keystore protected refresh credentials, bounded refresh, and local sign-out protection.
- Native API transport with exact origin checks, Problem Details mapping, explicit command idempotency keys, opaque ETag handling, and bounded replay after eligible HTTP 401 responses.
- Strict Gradle dependency verification and GitHub Actions gates for static checks, JVM tests, API 37 and API 29 emulator tests, endpoint validation, and release assembly with R8 minification and resource shrinking.

### Limitations

- Product workflows, Buyer Mobile, camera or scanner flows, and offline operation are outside this technical foundation.
- No production API endpoint or production Android distribution signing identity is configured. No APK or AAB is published for this source release.
- Product and UX acceptance, system acceptance, physical device acceptance, and production readiness are not claimed.
