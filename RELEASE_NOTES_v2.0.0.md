# Nexa Mobile v2.0.0 — source release candidate

**Status: candidate, unpublished.** Mobile [PR #72](https://github.com/nexa-suite/mobile/pull/72)
targets `main` at `16124927d1289a8566299aa52b2136fcb5f14a71`. Application
source commit `d1bf0fef2ed76e89cd26c16bf3e07905b5e9b18f` is the tested
baseline for this documentation-only release preparation. No tag or GitHub
Release has been published.

## Independent application versions

| Scope | Candidate metadata | Source |
| --- | --- | --- |
| Mobile repository | `v2.0.0` candidate | Repository source version; app versions remain independent. |
| Buyer Mobile | `1.2.1+4` | `apps/buyer-mobile/pubspec.yaml` |
| Operations Android | `1.3.0` (`versionCode` 12) | `apps/operations-android/app/build.gradle.kts` |

## Scope and API dependency

The candidate integrates Buyer Flutter and Operations Android work across
access, catalog, relationships, purchasing, delivery, Operations workflows and
accessibility. Buyer consumes the API's global delivery policy of five business
days in America/Lima, without holidays or a Workspace override. The API remains
authoritative for authorization and business decisions.

Compatible API work is tracked in [API PR #133](https://github.com/nexa-suite/api/pull/133).
Its bounded cloud read profile covers authentication, context, profile, catalog
and the Buyer's own account; other candidate workflows remain local and are not
accepted as connected cloud flows. Managed database migration and connected
validation remain pending. API migration review does not establish mobile
consumer compatibility or acceptance.

## Review and CI evidence

- `R0obxdnt` approved PR #72 at source commit
  `d1bf0fef2ed76e89cd26c16bf3e07905b5e9b18f` on 2026-11-02 at 15:23:47 UTC.
  A later documentation commit requires review of the new PR head.
- [Buyer Flutter CI](https://github.com/nexa-suite/mobile/actions/runs/41464120886)
  passed `buyer-quality` and `verify` on that source commit.
- [Operations Android CI](https://github.com/nexa-suite/mobile/actions/runs/41464120861)
  passed static checks, API 37 and tablet/IME checks, promotion and `verify` on
  that source commit. These runs do not verify the later documentation commit.
- The Buyer CI debug build uses `https://api.example.test`; its build does not
  establish connected workflow compatibility.

## Debug APK evidence

The candidate debug builds use the HTTPS API origin
`https://nexa-api-69bj.onrender.com` and share debug signing certificate SHA-256
`4de0129a321520e1303ea0fffb572ae240cd841f133a5a1b4a3b6378f5fa42b5`.
They are not distribution-signed release packages.

| APK | Source evidence | APK SHA-256 |
| --- | --- | --- |
| Buyer | `d1bf0fef2ed76e89cd26c16bf3e07905b5e9b18f` | `4b0955bed5f8208c3f9b61f2d108e1c8376b6050bc4eacf3a1c30a6b1aba8053` |
| Operations | `88beb35`; Operations tree `c117d6e3a4daa375d315cf38c1f4afefd8f1938d` is identical to the tree at `d1bf0fef` | `f39bcc8ecc902d9af0802d6a6bedaa1b9723635a1f8cb5ca1be10a75d5782195` |

Both APKs were installed on an API 37 ARM64 emulator. Buyer was also installed
on a Samsung device; the device was locked during the latest check. The existing
Operations 1.0.1 installation on that device uses a different certificate and
was preserved. Invalid cloud credentials returned HTTP 401 and were exercised in
the UI; successful authenticated journeys remain unverified. Buyer currently
shows a session-expired message for that invalid-login case.

## Remaining limits

A new human review and current-head CI are required after the documentation
commit. Compatible API publication, cloud migration, successful authenticated
end-to-end journeys and physical-device acceptance remain pending. No Product/UX
Acceptance, System Acceptance or Production Readiness is claimed.
