# Changelog

All notable changes to this repository are documented here using Keep a Changelog conventions and Semantic Versioning.

## [Unreleased]

### Documentation

- Corrected current suite release metadata and API runtime labels against published tag and GitHub Release evidence.
- Clarified published application/API integration evidence without selecting a Mobile technology or claiming deployment.

### Native engineering preview

- Added unmerged AV1 native Android preview slices for Nexa Operations:
  Compose, Navigation 3, feature-oriented modules, typed error mapping,
  Keystore-backed session material and localized launch/access states.
- Added typed API v0.17.0 access/session and SKU-resolution adapters with
  explicit configuration and fail-closed state handling.
- Added a Warehouse identification preview with CameraX/ML Kit barcode input,
  manual identifier input and server-owned resolution outcomes.
- Added host/CI/Docker verification commands pinned to the recorded JDK 21,
  Android API 36 and Gradle/AGP matrix, including project-graph and dependency
  hygiene checks.
- Kept live connected authentication blocked by unresolved `ClientSurface`
  semantics; live API, camera/decoder and physical-device runtime evidence is
  not claimed. An Android API 36 emulator smoke run now verifies installation,
  launcher activation and the fail-closed access-preview state only.

This entry does not publish `v0.2.0` and does not claim Mobile V1, live
API/camera runtime, physical-device or Product Acceptance evidence. The
emulator result is limited to the smoke boundary described above.

## [0.1.1] - 2026-07-28

### Added

- Historical publication snapshot: suite repository map aligned with Website `v1.0.0`, Platform/Portal `v0.3.0` and API `v0.4.0`.
- Explicit documentation-only release boundary and native-client decision gate.

## [0.1.0] - 2026-07-28

### Added

- Documentation-only repository foundation for future native Nexa mobile clients.

### Security

- Added coordinated vulnerability reporting guidance.

[0.1.0]: https://github.com/nexa-suite/mobile/releases/tag/v0.1.0
[0.1.1]: https://github.com/nexa-suite/mobile/compare/v0.1.0...v0.1.1
