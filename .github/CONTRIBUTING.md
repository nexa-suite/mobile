# Contributing to Nexa Mobile

## Welcome

Nexa Mobile contains an experimental AV1 native Android engineering preview
for Nexa Operations. It is not a Mobile V1 or production baseline.

## Before contributing

Read the root README, release notes and [SECURITY.md](./SECURITY.md). Keep
changes inside the approved AV1 scope. Do not add credentials, backend
endpoints or infrastructure without evidence and an explicit scope decision.

## Architecture boundaries

- Future clients consume explicit API contracts.
- Client models remain separate from backend domain classes.
- The current candidate client is Kotlin + Jetpack Compose on the recorded
  Android toolchain; this remains an engineering-preview choice, not a Product
  or Architecture decision.
- Do not introduce Flutter as a decision through implementation.
- Do not duplicate backend business rules or bypass API authorization.
- Mobile creates no Bounded Context. Keep access/session work aligned with
  `BC-01` and catalog identification aligned with `BC-03`.

## Development workflow

Use the selected project build JDK 21 and the Android SDK versions recorded in
the current Gradle and CI configuration. AGP 9.0.1's upstream minimum/default
JDK is 17:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
node scripts/validate-repository.mjs
```

Docker parity is available through the root `Dockerfile`; a host build does
not constitute emulator or physical-device evidence.

## Branch strategy

```text
feature/*
    ↓
develop
    ↓
release/vX.Y.Z
    ↓
main
    ↓
annotated tag
    ↓
GitHub Release
    ↓
back-merge to develop
```

## Commit convention

Use `type(scope): description`. Allowed types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `build`, `perf`, `security`.

## Testing requirements

Run the narrowest useful test first, then the complete native gates before a
review. Tests must prove state, tenant/security and contract properties rather
than only increase coverage. Do not claim authentication, authorization, SKU
resolution or device behavior from fakes or JVM tests.

## Documentation requirements

Update README, changelog and release notes when repository scope or planned client boundaries change.

## Security requirements

Never add secrets. Report vulnerabilities through [SECURITY.md](./SECURITY.md), never through public issues.

## Pull request checklist

- [ ] No generated artifacts.
- [ ] No duplicate Finder copies.
- [ ] No secrets.
- [ ] Native changes stay inside the approved AV1 stories and modules.
- [ ] No new API surface or infrastructure was invented.
- [ ] Host Gradle gates and repository validation pass.
- [ ] Documentation and release impact reviewed.

## Release process

Follow [RELEASE_POLICY.md](./RELEASE_POLICY.md). A native preview release
requires the applicable branch review, reproducible gates and truthful runtime
evidence in addition to the annotated signed-tag and back-merge rules.
