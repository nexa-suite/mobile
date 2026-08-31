# Release Policy

## Versioning

Nexa repositories version independently using Semantic Versioning. This repository is pre-1.0 and currently publishes documentation foundations only.

Every release requires an annotated tag, CHANGELOG entry, release notes and a GitHub Release. Published tags are immutable. Do not retag, modify a published version or force push.

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
- [ ] Annotated tag created.
- [ ] GitHub Release published.
- [ ] `develop` back-merged.

No build or runtime evidence is required while no implementation exists. Breaking changes and future client deprecations require explicit notes.
