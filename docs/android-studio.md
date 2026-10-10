# Open Operations Android in Android Studio

Open the Android project at `apps/operations-android` inside the local
`mobile-runtime` integration worktree. This is a local worktree on branch
`feature/w4-mobile-contract-closure`; it remains separate from the original
`mobile` checkout and does not imply integration into that checkout's branch.

Install JDK 17, Android SDK platform 37, and build tools 36.0.0. In Android
Studio, open `apps/operations-android`, allow Gradle sync to finish, then set
**Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**
to the installed JDK 17. Android Studio's Gradle daemon criteria target Java 17;
selecting a local JDK 17 is required for sync and builds. Do not rely on the
shell's `JAVA_HOME` to select the Android Studio Gradle JDK.

Keep dependency verification enabled. Gradle resolves dependencies in strict
mode from the checked-in `gradle/verification-metadata.xml`; Android Studio
sync also verifies artifacts against the recorded checksums. If a dependency
is rejected, verify its checksum from the repository publisher and update the
verification metadata through the repository's review process. Do not disable
verification to complete sync.

Select the `debug` build variant for local work. Debug uses
`http://10.0.2.2:8080/` by default so an Android emulator can reach an API
running on the development computer at `localhost:8080`. The emulator's own
`localhost` is the emulator device; `10.0.2.2` is its host loopback bridge.
The origin can be set for Gradle builds with
`-PnexaDebugApiBaseUrl=http://10.0.2.2:8080/`. The debug network security
configuration permits cleartext only for the configured local development
hosts. Keep release origins HTTPS and externally approved.

For a physical Android device, connect the local API through its selected ADB
serial instead of changing the emulator default:

```sh
scripts/reverse-local-api.sh "$ANDROID_SERIAL"
```

Use `-PnexaDebugApiBaseUrl=http://127.0.0.1:8080/` for that debug build. The
helper configures only the named device and maps host port 8080 to device
loopback.

Terminal verification uses the checked-in Gradle wrapper from
`apps/operations-android` and can make strict mode explicit:

```sh
./gradlew --dependency-verification strict :app:assembleDebug
```

No `local.properties` or machine-specific JDK/SDK path belongs in Git.
