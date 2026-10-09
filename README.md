# Nexa Operations Android

Native Android client for Nexa Operations. Open `apps/operations-android`
in Android Studio. The API owns authorization and business decisions;
the client renders server facts and coordinates scoped user requests.

## Structure

```text
apps/operations-android/
├── app/         Application entry points, dependency injection and navigation
├── contexts/    Context-owned client code
├── core/        Shared technical foundations
├── gradle/      Dependency versions, verification metadata and wrapper
├── licenses/    Third-party font licenses
└── scripts/     Architecture and connected-device checks
```

| Context | Directory | Runtime layers |
| --- | --- | --- |
| BC-01 Tenant & Access Governance | `tenantaccessgovernance` | All four |
| BC-02 Customer & Buyer Relationships | `customerbuyerrelationships` | All four |
| BC-03 Catalog & Commercial Policy | `catalogcommercialpolicy` | All four |
| BC-04 Sales Commitment | `salescommitment` | All four |
| BC-05 Inventory Availability | `inventoryavailability` | All four |
| BC-06 Fulfillment & Delivery | `fulfillmentdelivery` | All four |
| BC-07 Credit & Receivables | `creditreceivables` | Domain, application, infrastructure |
| BC-08 Payments | `payments` | None |
| BC-09 Business Documents | `businessdocuments` | All four |
| BC-10 Notifications | `notifications` | None |
| BC-11 Business Traceability | `businesstraceability` | None |

Each implemented layer is a Gradle module with its own `src`:

- `domain`: immutable client projections and local value constraints.
- `application`: ports, typed outcomes and workflow coordination.
- `infrastructure`: HTTP, serialization, protected storage and platform adapters.
- `presentation`: Compose screens, UI state and ViewModels.

Cross-context dependencies use `application.publicapi`. Contexts without
runtime code retain only their ownership note. `core` contains `auth`,
`network`, `local`, `device` and `designsystem` technical modules.

## Build and test

Use JDK 17, Android SDK `platforms;android-37.0` and
`build-tools;36.0.0`. The Gradle wrapper and dependency catalog define the
remaining toolchain. Minimum Android API is 29; target API is 37.

From `apps/operations-android`:

```sh
python3 scripts/verify-context-architecture.py
python3 scripts/test-context-architecture.py
./gradlew verifyAndroidArchitecture ktlintCheck lintDebug testDebugUnitTest \
  :app:assembleDebug --dependency-verification strict --console=plain
```

JVM results are under each module's `build/test-results/`. For native tests,
connect exactly one emulator or device and run:

```sh
scripts/verify-connected-local.sh <serial> \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.nexa.mobile.operations.RequiresPrivateFixture \
  --console=plain
```

Run the ordinary suite on API 29 and API 37. Private-fixture integration
requires separately provisioned accounts; never commit credentials.

Release assembly requires an approved non-local HTTPS API root:

```sh
./gradlew :app:assembleRelease \
  -PnexaReleaseApiBaseUrl="$APPROVED_NEXA_API_ORIGIN" \
  --dependency-verification strict
```

Distribution signing is configured outside the repository. Build success
does not establish functional acceptance or production readiness.

## Working conventions

Keep business authority in the API. Preserve Tenant, Workspace, membership
and session-epoch scope; missing scope fails closed. Keep access tokens in
memory and refresh credentials in protected local storage. Mutation replay
must preserve the original idempotency key and frozen payload.

Preserve unrelated local changes. Use signed commits and pull requests;
required checks and review must pass before integration. Keep documentation
archives, screenshots, experiments, credentials and build outputs local.
