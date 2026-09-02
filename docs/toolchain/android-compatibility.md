# Android toolchain evidence — AV1 native preview

Status: current local implementation evidence for the committed
`feature/native-foundation` branch plus explicitly deferred candidates; it is
not a canonical Nexa Product or Architecture decision.

Captured: 2026-09-01.

Task 2 resolution evidence: the first build attempt with BOM `2026.08.00`
failed at AAR metadata validation because Compose `1.12.0` required AGP
`9.1.0+` and compileSdk `37`. That candidate was rejected. BOM `2026.06.01`
resolved Compose `1.11.4` and passed the foundation build with AGP `9.0.1`,
Gradle `9.1.0`, project build JDK 21 and compileSdk `36`. AGP 9.0.1's
upstream minimum/default JDK is 17; JDK 21 is a project selection, not an AGP
requirement.

## Local host evidence

| Area | Observed value | Evidence / limitation |
| --- | --- | --- |
| Android CLI | `1.0.15985488` | `/Users/diegosandoval284/.local/bin/android --version` |
| Android SDK | `/Users/diegosandoval284/Library/Android/sdk` | `android info` |
| Android Studio | `2026.1` from bundle plist; installed bundle reports `2026.1.3 / Quail 3` | Local installation; exact project sync compatibility remains to be verified |
| Android platform | API 36 and API 37.0 installed | API 36 is the selected compile/target baseline; API 37 is not selected |
| Build tools | `36.0.0` installed | Selected for API 36 baseline |
| Platform tools | `37.0.1` / adb 1.0.41 | Installed locally |
| Emulator | `37.1.11.0` | No AVD and no connected device at capture time |
| Default terminal Java | OpenJDK `26.0.1` | Not selected for the Gradle build |
| Android Studio JBR | OpenJDK `25.0.2` | Not selected for the Gradle build |
| JDK 21 | Installed at `/Library/Java/JavaVirtualMachines/jdk-21.jdk` | `PROJECT BUILD JDK: 21`; selected and verified locally because it is the available LTS project/CI/Docker baseline |
| AGP upstream JDK | Minimum/default `17` | Official AGP 9.0.1 compatibility; JDK 17 is not installed on this host |

The host's Android CLI skills are installed and discoverable: `camerax`,
`navigation-3`, `testing-setup`, `android-intent-security`, `edge-to-edge`,
`android-cli` and `agp-9-upgrade`. Their guidance is applied selectively to
this slice; an installed skill is not proof that a dependency or runtime gate
passed.

## Selected candidate matrix

| Component | Candidate | Selection rule |
| --- | --- | --- |
| JDK | 21 LTS | `PROJECT BUILD JDK: 21`; selected and verified locally. AGP 9.0.1 upstream minimum/default is JDK 17 |
| Android Gradle Plugin | `9.0.1` | Stable candidate; compatible Gradle and Build Tools recorded below |
| Gradle | `9.1.0` | Required by the selected AGP candidate; distribution is cached locally |
| Kotlin / built-in Kotlin | KGP `2.2.10` effective baseline | AGP 9 built-in Kotlin is enabled by default and explicitly set true; no `org.jetbrains.kotlin.android` plugin |
| compileSdk / targetSdk | `36` / `36` | Android 16 API baseline; platform 36 is installed |
| minSdk | `23` | Deliberate support baseline for the AV1 preview; future scanner dependencies must be checked against it before adoption |
| Compose | BOM `2026.06.01` | Stable BOM candidate compatible with the selected AGP 9.0.1 / compileSdk 36 gate; `2026.08.00` was rejected because its Compose 1.12.0 artifacts require AGP 9.1.0+ and compileSdk 37 |
| Navigation 3 | `navigation3-runtime:1.1.7`, `navigation3-ui:1.1.7` | Stable line; exclude `1.2.0-beta01` |
| CameraX | `1.6.1` | Stable candidate; exclude 1.7 alpha builds |
| ML Kit barcode | bundled `com.google.mlkit:barcode-scanning:17.3.0` | On-device model; no dynamic Play Services model dependency |
| HTTP | Not added yet | Planned for the connected access slice; exact client versions remain deferred until the API adapter task |
| Serialization | Kotlin serialization plugin `2.2.10` / `kotlinx.serialization` `1.9.0` | Used by `:feature:access` for typed Navigation 3 routes; no HTTP runtime is claimed by the foundation |
| Lifecycle | `2.10.0` | Effective selected version for the access launch state and Navigation 3 dependency graph |
| DI | Manual constructor-based DI | Keeps the foundation small and avoids an unverified Hilt/KSP matrix |
| JVM tests | JUnit 4.13.2; AndroidX Test line as needed | Focused unit tests first; no test dependency added without use |

## Kotlin and compiler strategy

- Built-in Kotlin: enabled through AGP 9.0.1's default and explicit
  `android.builtInKotlin=true` project property.
- `org.jetbrains.kotlin.android`: not applied.
- Effective KGP: `2.2.10`, supplied by AGP 9.0.1's runtime dependency. The
  version catalog repeats `2.2.10` for the Compose compiler plugin and later
  typed-serialization modules.
- Compose compiler: `org.jetbrains.kotlin.plugin.compose`, applied by the
  Android modules that use Compose; no legacy compiler extension is configured.
- JVM target: Gradle itself runs with the selected project JDK 21; Android
  Java/Kotlin compilation targets JVM 17 for AGP compatibility.

## Required build configuration consequences

- Use a single `:operations` application with moderate `:core:*` and
  `:feature:*` modules only where the slice has a real boundary.
- Use `enableEdgeToEdge()` before `setContent` and one inset strategy based on
  `WindowInsets.safeDrawing`; add explicit IME/window soft-input behavior only
  when a real keyboard/form flow exists.
- Declare `android:exported="true"` only for the launcher Activity. Services,
  receivers and providers are absent unless a later requirement proves one.
- Do not fail developers on every supported JVM solely because it is not major
  version 21. JDK 21 is the selected project/CI/Docker build JDK; the root
  build does not claim that AGP requires exactly JDK 21.
- Disable cloud/device transfer through explicit backup rules and provide a
  deterministic launcher icon for the preview APK.
- Keep CameraX analysis lifecycle-bound, close every `ImageProxy`, drop stale
  frames and pause duplicate resolution.
- Keep passwords out of storage. Protect session material with Android
  Keystore; use DataStore only for safe preferences/non-authoritative state.
- Keep fakes and contract tests separate from claims of live authentication,
  authorization or SKU resolution.

## Official references used for the candidate matrix

- [Android Studio releases](https://developer.android.com/studio/releases)
- [AGP 9.0.1 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
- [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Android 16 SDK setup](https://developer.android.com/about/versions/16/setup-sdk)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [Lifecycle releases](https://developer.android.com/jetpack/androidx/releases/lifecycle)
- [Navigation 3 releases](https://developer.android.com/jetpack/androidx/releases/navigation3)
- [CameraX releases](https://developer.android.com/jetpack/androidx/releases/camera)
- [ML Kit barcode scanning](https://developers.google.com/ml-kit/vision/barcode-scanning/android)

## CI and Docker reproduction matrix

The GitHub workflow installs the selected project build JDK 21, accepts the selected Android SDK
licenses, installs `platform-tools`, `platforms;android-36` and
`build-tools;36.0.0`, validates the executable Gradle wrapper and runs the
same host gates:

```text
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

The wrapper pins the official Gradle distribution SHA-256
`b84e04fa845fecba48551f425957641074fcc00a88a84d2aae5808743b35fc85`.

The Docker build pins the Eclipse Temurin project build JDK 21 image by digest and verifies
the official Android command-line-tools archive with SHA-256 before installing
the same SDK packages. Reproduce it with:

```bash
docker build -t nexa-mobile-av1-build .
docker run --rm nexa-mobile-av1-build
```

The image command runs the three Gradle gates in an ephemeral container, so
credentials and host build outputs are excluded by `.dockerignore`. Docker
parity is not run by the current GitHub workflow because the repository's
30-minute hosted runner budget is reserved for the native gates; it remains a
release gate and must be recorded as `BLOCKED — DOCKER BUILD` if unavailable.

## Open gates

- CameraX, ML Kit, Retrofit and OkHttp resolution remains open for the
  connected/warehouse slices; they are intentionally not added to the
  foundation.
- No emulator evidence exists until an AVD is intentionally created and the
  application is installed and exercised.
- `BLOCKED — AUTH SURFACE SEMANTICS` remains active until the accepted API
  mapping for Operations Mobile is supplied.

## Foundation build evidence

Verified locally in the committed foundation worktree with
`ANDROID_HOME=/Users/diegosandoval284/Library/Android/sdk` and
`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`:

```text
./gradlew testDebugUnitTest lintDebug assembleDebug       PASS
```

The Diego bootstrap scope was independently verified from the base commit with
only its files. The current foundation branch additionally passes the Gerard,
Joaquin and Gino module/repository gates. Lint reports zero errors and 14
warnings: one target-SDK compatibility notice and the remaining notices are
newer-version suggestions for deliberately selected stable toolchain lines.

The generated debug APK is ignored by repository hygiene rules. Manifest
inspection shows the application identity, the exported launcher Activity and
the AndroidX Startup provider marked `exported="false"`. AndroidX also merges
its `ProfileInstallReceiver`, which is `exported="true"` and protected by
`android.permission.DUMP`; it is framework-owned and retained because the
profileinstaller dependency is not artificially excluded.
