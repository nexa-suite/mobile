# Release Policy

## Versioning

Nexa repositories version independently using Semantic Versioning. This repository is pre-1.0 and currently publishes documentation foundations only.

Every release requires an annotated SSH-signed tag, CHANGELOG entry, release notes and a GitHub Release. The tag must pass local and GitHub verification before publication. Published tags are immutable. Do not retag, modify a published version, delete a release or force-push history.

## Tag signing

Release tags MUST be annotated, signed with the repository maintainer's
registered SSH signing key, verified locally with `git verify-tag <version>`
and shown as `Verified` by GitHub before publication. The private key remains
outside the repository; `.github/release-allowed-signers` contains only the
public signer identity.

## Release cadence

A merged PR is not automatically a release. Accumulate documentation and
future-client runway changes until a coherent milestone exists. Do not claim
native build, device or runtime verification while no implementation exists.

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
