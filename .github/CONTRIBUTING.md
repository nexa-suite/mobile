# Contributing to Nexa Mobile

## Welcome

Nexa Mobile is reserved for future native clients. This repository currently contains documentation only.

## Before contributing

Read the root README, release notes and [SECURITY.md](./SECURITY.md). Do not create an application project, add SDKs or add credentials without an approved scope.

## Architecture boundaries

- Future clients consume explicit API contracts.
- Client models remain separate from backend domain classes.
- Kotlin and SwiftUI are planned, not selected.
- Do not introduce Flutter as a decision through implementation.
- Do not duplicate backend business rules or bypass API authorization.

## Development workflow

There is no application build or runtime command in the current repository foundation.

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

No runtime tests exist. Documentation changes must have checked relative links, Mermaid syntax and release impact.

## Documentation requirements

Update README, changelog and release notes when repository scope or planned client boundaries change.

## Security requirements

Never add secrets. Report vulnerabilities through [SECURITY.md](./SECURITY.md), never through public issues.

## Pull request checklist

- [ ] No generated artifacts.
- [ ] No duplicate Finder copies.
- [ ] No secrets.
- [ ] No application project added without approved scope.
- [ ] Documentation and release impact reviewed.

## Release process

Follow [RELEASE_POLICY.md](./RELEASE_POLICY.md). Documentation releases still require an annotated tag, GitHub Release and back-merge.
