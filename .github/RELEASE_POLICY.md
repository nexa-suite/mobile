# Release Policy

## Versioning

Nexa repositories version independently using Semantic Versioning. This repository is pre-1.0 and currently publishes documentation foundations only.

Every release requires an annotated and SSH-signed tag, CHANGELOG entry, release notes and a GitHub Release. Published tags are immutable during normal release operations; an explicitly authorized SCM history migration may reissue a tag only when its target commit is preserved and the release record is audited.

## Tag signing

Release tags MUST be annotated, signed with the maintainer's registered SSH key, verified locally with `git verify-tag <version>` and shown as `Verified` by GitHub before publication. Configure `tag.gpgSign=true` and `gpg.ssh.allowedSignersFile=.github/release-allowed-signers`; the committed allowlist contains only the public signer identity. The private key remains outside the repository.

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

## Release cadence

A merged PR is not automatically a release. Accumulate documentation, feature
and fix PRs on `develop` while the coherent scope is being assembled. Create a
release branch only at a real release boundary, then validate and publish one
consumable milestone. Use release candidates only when final validation needs a
candidate freeze. Do not publish calendar-driven versions or one stable
release per implementation PR.

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
