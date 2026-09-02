plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val nativeDebugUnitTestTasks = listOf(
    ":core:designsystem:testDebugUnitTest",
    ":core:network:testDebugUnitTest",
    ":core:storage:testDebugUnitTest",
    ":core:testing:testDebugUnitTest",
    ":feature:access:testDebugUnitTest",
    ":feature:warehouse:testDebugUnitTest",
    ":operations:testDebugUnitTest",
)

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs all native debug unit tests."
    dependsOn(*nativeDebugUnitTestTasks.toTypedArray())
}

tasks.register("lintDebug") {
    group = "verification"
    description = "Runs the Operations debug lint checks."
    dependsOn(":operations:lintDebug")
}

tasks.register("assembleDebug") {
    group = "build"
    description = "Assembles the Operations debug APK."
    dependsOn(":operations:assembleDebug")
}
