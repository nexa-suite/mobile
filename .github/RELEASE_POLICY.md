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
