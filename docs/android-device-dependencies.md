# Android device dependency provenance

## Camera and bundled barcode decoding — 2026-09-30

The device adapter pins CameraX `1.6.2` (`camera-camera2`, `camera-lifecycle`,
`camera-mlkit-vision` and `camera-view`) and bundled ML Kit
`com.google.mlkit:barcode-scanning:17.3.0`. The versions come from the
[CameraX release record](https://developer.android.com/jetpack/androidx/releases/camera)
and [bundled barcode integration contract](https://developers.google.com/ml-kit/vision/barcode-scanning/android).
No existing declared dependency version changed.

Checksum admission added 168 artifact records: 57 AARs, 18 JARs, 51 POMs and
42 Gradle module metadata files. Each SHA-256 was compared with the bytes
downloaded independently from Google's official Maven repository
(`https://dl.google.com/dl/android/maven2/`) or Maven Central
(`https://repo.maven.apache.org/maven2/`) over verified HTTPS. All matched.
The existing 880 artifact records and verification configuration remained
unchanged. No trusted-artifact wildcard or verification bypass was added.

The published `androidx.tracing:tracing-android:1.3.0` module names its AAR
`tracing.aar`, while its declared download URL uses the versioned filename.
Its admitted checksum matches both the official module's SHA-256 declaration
and the downloaded AAR bytes.

Metadata generation was an admission step followed by independent source
verification. Subsequent builds retain `--dependency-verification strict`.
Dependency resolution evidence is separate from compilation, device behavior,
physical-camera evidence and Product Acceptance.
