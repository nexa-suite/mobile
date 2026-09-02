plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the Operations debug unit tests."
    dependsOn(":operations:testDebugUnitTest")
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
