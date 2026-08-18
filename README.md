<div align="center">

<br />

<img src="./docs/assets/nexa.svg" alt="Nexa" width="240" />

# Nexa Mobile

**Future native runway for buyer and cold-chain field experiences.**

![Status](https://img.shields.io/badge/status-planned-64748B?style=flat-square) ![Documentation](https://img.shields.io/badge/scope-documentation-64748B?style=flat-square) ![Native Runway](https://img.shields.io/badge/native-runway-64748B?style=flat-square)

[Changelog](./CHANGELOG.md) · [Release notes](./docs/releases/) · [Contributing](./.github/CONTRIBUTING.md) · [Security](./.github/SECURITY.md)

</div>

---

## Overview

Mobile is Architecture Runway, not V1 implementation. Repository contains documentation and release guidance only. No native application is implemented here.

## Related repositories

The organization profile owns the full public ecosystem map. This repository links to adjacent Nexa surfaces without copying their release state.

- [Nexa API](https://github.com/nexa-suite/api) — business and integration backbone.
- [Nexa Platform](https://github.com/nexa-suite/platform) — internal operational workspace.
- [Nexa Buyer Portal](https://github.com/nexa-suite/portal) — buyer-facing experience.
- [Nexa Website](https://github.com/nexa-suite/website) — public product experience.

## Mobile Runway

- Buyer access and purchasing on native clients.
- Warehouse, Logistics and Dispatch field workflows.
- Approved offline, identity, tenant and API contract decisions.
- Client models kept separate from backend domain code.

No mobile technology has been selected. No runtime, build or production claim is made.

## Architecture Boundary

Future clients consume approved API contracts. Authorization, tenant scope and business rules remain backend responsibilities. Mobile is deliberately absent from implemented runtime map.

## Technology Stack

| Concern | Status |
| --- | --- |
| Application framework | Not selected |
| Native clients | Planned |
| Backend authority | Nexa API |
| Current repository role | Documentation and architecture runway |

## Getting Started

No application setup, dependency installation or runtime command exists yet.

## Repository Structure

    README.md
    CHANGELOG.md
    docs/assets/nexa.svg
    docs/releases/
    .github/

## Documentation

- [Release notes](./docs/releases/)
- [Release policy](./.github/RELEASE_POLICY.md)


## Historical provenance

Earlier UPC repositories remain evidence only. They do not define current Nexa identity, implementation authority or TARGET architecture.

- [nexa-platform](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-platform) — predecessor backend and REST API layer.
- [nexa-webapp](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-webapp) — historical unified Vue application.
- [nexa-website](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-website) — previous public Website lineage.
- [nexa-ecosystem-report](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-ecosystem-report) — historical requirements and architecture evidence.


## Security

Do not report vulnerabilities through public issues. Follow the repository [Security Policy](./.github/SECURITY.md).

## Legal

Copyright © 2026 Nexa. All rights reserved. No open-source license is selected by this README.

<div align="center"><br />Nexa · Current product, explicit evidence boundaries</div>
