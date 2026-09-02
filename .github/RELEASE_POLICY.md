# Release Policy

## Versioning

Nexa repositories version independently using Semantic Versioning. This
repository is pre-1.0; `v0.1.x` publishes documentation foundations only,
while the proposed `v0.2.0+` milestones may publish explicitly labelled
engineering-preview source.

Every release requires an annotated SSH-signed tag, CHANGELOG entry, release notes and a GitHub Release. The tag must pass local and GitHub verification before publication. Published tags are immutable. Do not retag, modify a published version, delete a release or force-push history.

## Tag signing

Release tags MUST be annotated, signed with the repository maintainer's
registered SSH signing key, verified locally with `git verify-tag <version>`
and shown as `Verified` by GitHub before publication. The private key remains
outside the repository; `.github/release-allowed-signers` contains only the
public signer identity.

## Release cadence

A merged PR is not automatically a release. Accumulate changes until a
coherent milestone exists. A native-preview release must identify its exact
stories, branch/ref and API evidence, and must not claim Mobile V1, Product
Acceptance or production readiness.

## Native preview gate

Before proposing a native-preview release:

- The complete diff has been reviewed and accepted by the human owner; AI must
  not fabricate authorship, review, PR history or co-authors.
- `./gradlew testDebugUnitTest lintDebug assembleDebug` passes with the
  recorded JDK/SDK matrix.
- The Docker image is built from the pinned `Dockerfile` and runs the same
  gates, or the release notes record `BLOCKED — DOCKER BUILD` with the exact
  command and reason.
- Emulator and physical-device evidence are reported separately. A host build
  never substitutes for either runtime gate.
- API integrations reference the exact read-only compatibility tag and any
  unresolved semantic blocker remains visible.

## GitFlow

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

## Checklist

- [ ] Scope approved.
- [ ] Working tree clean.
- [ ] Documentation links and Mermaid checked.
- [ ] Security review completed.
- [ ] CHANGELOG and release notes updated.
- [ ] Version updated.
- [ ] Release branch created.
- [ ] `main` merged with `--no-ff`.
- [ ] Annotated SSH-signed tag created.
- [ ] `git verify-tag <version>` passed locally and GitHub shows `Verified`.
- [ ] GitHub Release published.
- [ ] `develop` back-merged.

No build or runtime evidence is required while no implementation exists. Breaking changes and future client deprecations require explicit notes.
