# Mobile identifier and local recovery evidence

**Status: partial research pack; the decoder-only scanner PoC passed on an API 37 emulator; SPIKE-004 reproduction checks remain pending.**
Prepared 2026-10-08 against Blueprint `main` at `9034f4857b45224832f61af3e088f8a29143b187`.
This record adds no Product behavior, identifier scope, offline authority, or
retention policy. It is evidence for SPIKE-003/004 only.

The accepted Mobile story criteria remain in Blueprint
`03-mobile/requirements/mobile-v1-catalog.md` (MOB-US-011/012/013/014) and its
camera/offline decisions. V1 camera input is EAN, UPC and QR; GS1 remains future
unless Product authority changes that boundary. A decoded value is only an
untrusted candidate: Catalog/API resolution must reject unknown, ambiguous or
out-of-scope identifiers, and scanning cannot create stock facts. Manual search
remains the required fallback.

## SPIKE-003 — identifier formats and decoder PoC

Google's current Android ML Kit documentation lists the supported linear and 2D
formats, including EAN-13, UPC-A/UPC-E, QR, Data Matrix, Code 128, PDF417 and
others. It documents the bundled `com.google.mlkit:barcode-scanning` artifact,
`InputImage.fromBitmap`, decoded `rawValue`, and image-size/focus constraints.
ML Kit also notes that Data Matrix recognition requires the symbol to intersect
the image center and recognizes at most one Data Matrix per image.
The Operations device module already depends on this bundled artifact at
17.3.0; this PoC adds no production dependency. See [ML Kit Barcode Scanning for
Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android).

GS1 distinguishes the carrier from the data structure: GS1 DataMatrix carries
GS1 element-string syntax, while QR Code and Data Matrix used for GS1 Digital
Link carry a Digital Link URI. These symbols can encode Application
Identifiers such as GTIN, lot and expiry, but a generic QR decode does not
validate that syntax, validate an AI, or establish a product identity. GS1 also
documents that 2D support varies by system during transition. Keep legacy
1D/fallback paths and do not promote arbitrary QR content to a SKU. See the
[GS1 2D barcode comparison](https://support.gs1.org/support/solutions/articles/43000756000-what-is-the-difference-between-the-2d-barcode-options-gs1-datamatrix-data-matrix-with-gs1-digital-l),
[GS1 General Specifications](https://ref.gs1.org/standards/genspecs/), and
[GS1 Application Identifiers](https://www.gs1.org/gs1-application-identifiers).

The instrumented PoC is
[BarcodeDecodingPoCTest.kt](../../apps/operations-android/core/device/src/androidTest/kotlin/com/nexa/mobile/operations/core/device/scanner/BarcodeDecodingPoCTest.kt).
It calls bundled `BarcodeScanning`, creates `InputImage` from static bitmap
fixtures, and waits on the ML Kit task with `Tasks.await`. The checked-in QR and
EAN-13 images contain only synthetic values. A blank bitmap is the negative
case. The EAN-13 fixture is a decoder-capability sample, not an allocated GTIN
or server-valid identifier. This tests image decoding only: it does not use
CameraX, camera permissions, autofocus, physical lighting, or a live API; it
does not test server-side unknown/ambiguous/scope rejection or GS1 parsing. No
raw value should be logged, persisted, or sent anywhere by this test.

| Artifact | Synthetic value | What it can establish after the test runs |
|---|---|---|
| `synthetic-qr.png` | `NEXA-SYNTHETIC-PRODUCT-042` | ML Kit can decode this QR bitmap and return its raw string. |
| `synthetic-ean13.png` | `1234567890128` | ML Kit can decode this synthetic, valid-check-digit EAN-13 bitmap and return its raw digits. |
| In-memory blank bitmap | no encoded value | A non-barcode image yields no decoded candidate. |

**Reproduction command — passed on 2026-10-08:**

```sh
cd apps/operations-android
./gradlew :core:device:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nexa.mobile.operations.core.device.scanner.BarcodeDecodingPoCTest \
  --dependency-verification strict --console=plain
```

The command passed on emulator `Nexa_DDD_API37(AVD)` running API 37: 2 tests,
0 failures, 0 skipped. This establishes bitmap decoding for the synthetic QR
and EAN-13 inputs plus the blank-image negative case on that emulator. It does
not establish physical-camera or real-label behavior. The test uses the
AndroidJUnit4 runner plus `androidx.test.ext:junit` and `androidx.test:runner`
in `core/device`'s `androidTest` configuration; no ML Kit production
dependency change was needed.

## SPIKE-004 — local data, protection and recovery boundary

The table separates the current receiving implementation from open retention
questions. Local records assist safe recovery; they do not grant permission,
confirm stock, or replace API results. Blueprint's mobile storage and
synchronization targets prohibit offline business success, generic transaction
sync and client last-write-wins behavior.

| Data class | Permitted purpose / content | Current protection and scope | Expiry or invalidation | Authority boundary |
|---|---|---|---|---|
| Access/refresh credentials, permission grants, provider/payment secrets | None in receiving metadata. | Not stored by this workflow. Session credentials remain in the auth boundary. | Auth lifecycle only; never copy into a feature draft or command. | Forbidden in local feature records. |
| Scope identity | `userId`, `tenantId`, `workspaceId`, `membershipId` partition and authenticate one local record. | Scope fields are length-prefixed; the file key is SHA-256 of that encoding. AES-GCM additional authenticated data also binds the full scope. | Invalidate on logout, identity/session change, Tenant, Workspace or Membership change. No time TTL is defined here. | Identifies which local record may be read; it is not an authorization grant. |
| Read projections and product references | Minimal identifiers/display fields needed to resume a form or show a read result. | Receiving metadata is encrypted in `noBackupFilesDir`; restored product references require fresh Catalog confirmation. | Refresh when source freshness/version is stale and on scope change. Blueprint specifies no fixed cache TTL. | A projection is not current inventory or confirmed product identity. |
| Harmless receiving draft | User-entered product reference, warehouse/zone, lot, expiry, quantity, temperature and notes. | Stored with the full scope in one encrypted `AtomicFile` record; malformed or unreadable data fails closed. | Clear on scope invalidation or explicit completion/cancel. No accepted wall-clock expiry was found; do not invent one. | Draft values remain unconfirmed; they cannot make stock sellable. |
| Frozen receiving intent | Idempotency key, full scope, command fields and `Pending`/`UnknownOutcome` status. | Encrypted with the draft. The AndroidKeyStore AES-GCM key uses a random 12-byte IV and 128-bit tag; scope/domain header is authenticated data. | Preserve until a matching terminal response and successful key-checked clear, or explicit recovery. No automatic dispatch or generic retry queue. | Restored `Pending` is surfaced as `UnknownOutcome`; retry is explicit, uses the same key and frozen request values, and still requires current authority. Server response alone confirms the receipt. |
| Photo/object evidence bytes | Only temporary private evidence staging where a workflow requires it; metadata may retain an object reference. | Evidence-byte storage is outside this receiving metadata record and must use its own private/encrypted storage and exact-subject upload checks. | Remove temporary bytes after confirmed upload or cancellation under an accepted retention rule. No universal duration is set here. | A local file/reference is not accepted evidence or a disposition decision. |
| Logs/analytics | No raw barcode, notes, token, full scope identity or frozen payload. | Use redacted event/status fields only. | Retain only under the app's approved diagnostics policy. | Never use client telemetry to infer a business fact. |

**Open retention question:** the accepted sources define freshness and
scope-change behavior, but do not set universal wall-clock TTLs for drafts,
cached projections or evidence bytes. This document intentionally leaves those
durations open for the owning workflow/security decision; it does not create a
Product promise.

The receiving storage implementation and its tests now belong to BC-05
infrastructure. `:core:local` supplies only the generic scoped-storage
mechanics used by context-owned adapters. Current source and test references are:

- [Receiving store and authenticated scope codec](../../apps/operations-android/contexts/inventoryavailability/infrastructure/src/main/kotlin/com/nexa/mobile/operations/inventoryavailability/infrastructure/storage/receiving/AndroidReceivingMetadataStore.kt)
- [Receiving metadata models](../../apps/operations-android/contexts/inventoryavailability/infrastructure/src/main/kotlin/com/nexa/mobile/operations/inventoryavailability/infrastructure/storage/receiving/ReceivingMetadataModels.kt)
- [Receiving JVM core tests](../../apps/operations-android/contexts/inventoryavailability/infrastructure/src/test/kotlin/com/nexa/mobile/operations/inventoryavailability/infrastructure/storage/receiving/ReceivingMetadataStoreCoreTest.kt)
- [Android Keystore and AtomicFile tests](../../apps/operations-android/contexts/inventoryavailability/infrastructure/src/androidTest/kotlin/com/nexa/mobile/operations/inventoryavailability/infrastructure/storage/receiving/AndroidReceivingMetadataStoreTest.kt)
- [Generic scoped storage adapter](../../apps/operations-android/core/local/src/main/kotlin/com/nexa/mobile/operations/core/local/scoped/AndroidScopedMetadataStore.kt)
- [Durable intent coordinator tests](../../apps/operations-android/contexts/inventoryavailability/application/src/test/kotlin/com/nexa/mobile/operations/inventoryavailability/application/warehouse/ReceivingIntentCoordinatorTest.kt)
- [Receiving ViewModel retry tests](../../apps/operations-android/contexts/inventoryavailability/presentation/src/test/kotlin/com/nexa/mobile/operations/inventoryavailability/presentation/warehouse/ReceivingViewModelTest.kt)

The test sources cover full-scope binding, ciphertext copy/corruption, missing
keys, stale clears, same-key replay, frozen decimal values, stale authority
epochs and a late clear. The migrated BC-05 unit and Android tests have not yet
passed on the current worktree. Recreating a store instance is not a
process-death test. The repository's earlier
[receiving verification](../receiving-verification.md) records an earlier
source checkpoint; its results are not asserted for the current DDD worktree.

**Reproduction commands — all results pending for this worktree:**

```sh
cd apps/operations-android
./gradlew :contexts:inventoryavailability:application:test \
  :contexts:inventoryavailability:presentation:testDebugUnitTest \
  :contexts:inventoryavailability:infrastructure:testDebugUnitTest \
  --dependency-verification strict --console=plain

./gradlew :contexts:inventoryavailability:infrastructure:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.receiving.AndroidReceivingMetadataStoreTest \
  --dependency-verification strict --console=plain
```

Neither command was executed while preparing this record. Passing the existing
store reconstruction test would still not prove OS process death, forced
termination during a write, restore from backup, API 29 behavior, or end-to-end
server deduplication. Those claims require separate, named tests and evidence.
