# Operations Android verification

Use this guide for repeatable technical checks. The [DDD client verification
record](ddd-client-verification.md) contains results tied to historical source
checkpoints; the [v1.2.0 candidate notes](releases/v1.2.0.md) state the current
source scope and release limits. A result from an older commit does not verify a
newer one.

## Toolchain

Run Gradle commands from `apps/operations-android` with JDK 17. Install Android
SDK platform 37, build-tools 36.0.0 and platform-tools. Keep dependency
verification enabled; inspect the source and published checksums of a new
artifact before updating verification metadata.

## Static, JVM and debug build gates

From `apps/operations-android`:

```sh
python3 scripts/verify-context-architecture.py
./gradlew verifyAndroidArchitecture ktlintCheck lintDebug testDebugUnitTest \
  :app:assembleDebug --dependency-verification strict --console=plain
```

The Python check enforces the eleven canonical context roots and layer
boundaries. The Gradle tasks cover architecture, formatting, lint, configured
JVM tests and debug assembly. Inspect the XML reports before recording test
counts: Android module reports are under
`<module>/build/test-results/testDebugUnitTest`; Kotlin/JVM domain and
application reports are under
`contexts/<root>/{domain,application}/build/test-results/test`.

## Emulator checks

Use one booted emulator at a time. Record its serial and Android API level, then
run the connected suite from `apps/operations-android`:

```sh
adb devices -l
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk
scripts/verify-connected-local.sh "$ANDROID_SERIAL" \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.nexa.mobile.operations.RequiresPrivateFixture \
  --dependency-verification strict --console=plain
```

Use API 37 for feature verification and API 29 for promotion verification. The
helper refuses to run when multiple devices are connected. Connected test XML
is under each module's
`build/outputs/androidTest-results/connected/debug`. Preserve the source commit,
device API, exact command and observed XML counts with any local evidence.

Tests marked `RequiresPrivateFixture` need separately configured local
credentials and are excluded from the ordinary run. Never put credential
values in source, reports, command transcripts or screenshots. A skipped
private-fixture test is not evidence of live API integration.

## Release build checks

Release assembly needs a non-local HTTPS validation origin supplied at build
time. Do not store production endpoints or signing credentials in the
repository.

```sh
./gradlew :app:validateReleaseEndpoint \
  -PnexaReleaseApiBaseUrl="$NEXA_VALIDATION_API_ORIGIN" \
  --dependency-verification strict
./gradlew :app:assembleRelease \
  -PnexaReleaseApiBaseUrl="$NEXA_VALIDATION_API_ORIGIN" \
  --dependency-verification strict --console=plain
```

Also verify that endpoint validation rejects an omitted value, cleartext or
local origins, and obvious placeholders. A successful unsigned R8 build does
not establish live API compatibility, distribution signing or production
readiness.

## CI

The required Android workflow is
`.github/workflows/android-verify.yml`. Inspect the individual job results and
the aggregate `verify` status on the exact pushed commit. Its release-origin
and API 29 promotion gates apply to pull requests targeting `main` and pushes
to `develop` or `main`; feature-branch checks include static/JVM gates, debug
assembly and API 37 instrumentation. Local results do not replace CI evidence
for the commit under review.
