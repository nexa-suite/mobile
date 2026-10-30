# Nexa Mobile v1.3.1 — source release candidate

**Status: local candidate, unpublished.** Prepared on 2026-10-29 from `main` at `22748321730b1992092064537fd4c24497b6f935`. No remote `v1.3.1` tag or GitHub Release exists.

## Independent app versions

| Scope | Candidate | Reason |
| --- | --- | --- |
| Mobile source tag | `v1.3.1` | Repository-level patch candidate after published `v1.3.0`. |
| Buyer Mobile | `1.2.1+4` | Buyer catalog permission/error recovery fix; next app patch and build number. |
| Operations Android | `1.3.0` (`versionCode` 12) | Runtime source unchanged; changes are an instrumentation test and academic APK workflow. |

## Scope

- Buyer catalog clears stale items, totals, page and update time after a `403`, and displays a Spanish fallback for unexpected errors.
- Buyer tests cover later-page `403` recovery after sign-in and the unexpected-error fallback.
- Operations' profile/logout instrumentation test scrolls the profile sheet before selecting logout.
- The manually triggered academic APK workflow derives its artifact name from APK version metadata and includes a generated SHA-256 sidecar.

## Verification

- Buyer architecture checks passed: 11 tests; 86 Dart sources respected context boundaries.
- `dart format --output=none --set-exit-if-changed lib test` passed: 115 files, 0 changes.
- `flutter analyze --no-pub` passed with no issues.
- `flutter test --no-pub` passed: 170 tests passed; 5 opt-in local API integration tests skipped. Local SDK was Flutter 3.47.8; CI pins 3.47.7.
- `flutter build apk --debug --no-pub --dart-define=NEXA_API_BASE_URL=https://api.example.test` passed. This is a debug APK, not a signed release distribution artifact.
- Exact `main` commit CI: [Buyer run 40982599488](https://github.com/nexa-suite/mobile/actions/runs/40982599488) completed, but `buyer-quality` was skipped; [Operations run 40982599386](https://github.com/nexa-suite/mobile/actions/runs/40982599386) passed static checks, API 37 instrumentation, tablet IME instrumentation and promotion checks.

## Release boundary

This candidate is appropriate as a source-only repository release after review. No signed Operations APK, Buyer distribution artifact, or iOS package is included. CI emulator evidence does not establish physical-device acceptance. Product Acceptance, System Acceptance, and Production Readiness remain separate and unverified.
