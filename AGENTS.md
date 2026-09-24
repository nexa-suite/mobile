# Repository working agreement

- Follow accepted Product and architecture decisions in the Nexa Blueprint repository and the current API contract. This repository implements the Android client; it does not define server authorization or Product behavior.
- Keep the four-module boundary. `:core:auth` owns protected local session state and must not import Retrofit, OkHttp, or `:core:network`. `:core:network` owns HTTP transport and must not implement Product permission decisions. Presentation must not consume raw transport DTOs.
- Keep access tokens in process memory. Protect refresh credentials with AndroidKeyStore, AES-GCM, and an atomic record in `noBackupFilesDir`. Persist `IN_FLIGHT` without the old credential before dispatching a refresh. Never retry an ambiguous rotation with the old credential.
- Attach secret headers only to the configured API origin. Keep native auth headers on their verified routes. Do not add transparent mutation retry or global idempotency keys.
- Add focused JVM, MockWebServer, and Android instrumentation coverage with behavior changes. Run architecture, ktlint, lint, dependency verification, and the relevant emulator gate before proposing integration.
- Use signed Conventional Commits on feature branches. Preserve authorship and history. Do not force push, merge without required review, publish releases, or introduce distribution signing material through implementation work.
