# Android Studio: Java 17 daemon discovery on macOS

The project daemon criteria require Java 17. A Java installation used by the shell can be different from the installation Gradle selects for its daemon. If sync reports that no matching toolchain can be downloaded and the download URL is null, make an existing Java 17 installation discoverable before changing project criteria.

On macOS, check installed Java homes and the project criteria:

```sh
/usr/libexec/java_home -V
cat gradle/gradle-daemon-jvm.properties
```

A Homebrew Java installation may exist without appearing in the macOS Java registry. In that case, add its absolute Java home to the user-level `~/.gradle/gradle.properties` property `org.gradle.java.installations.paths`. Preserve existing entries and other properties. The value must point to the JDK home, not its `bin/java` executable. This is machine configuration and must not be committed to the project.

Validate discovery with the ordinary launcher, keeping strict dependency verification:

```sh
./gradlew --dependency-verification strict --version
./gradlew --dependency-verification strict verifyAndroidArchitecture testDebugUnitTest
```

Then sync the project in Android Studio and assemble the debug application. Command-line success does not by itself establish that the IDE model imported successfully.

On 2 October 2026, the existing local Java 17 installation was registered through the user-level discovery property. Android Studio sync and `:app:assembleDebug` succeeded. The architecture and JVM verification task invocation also succeeded; most tasks were up to date, so this follow-up does not constitute a fresh rerun of all tests. Project daemon criteria, wrapper configuration and strict dependency verification remained in place.

References: [Gradle daemon JVM criteria](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria) and [Java toolchain discovery](https://docs.gradle.org/current/userguide/toolchains.html#sec:custom_loc).
