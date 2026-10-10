plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.nexa.mobile.operations.notifications.infrastructure"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

ktlint { version.set("1.8.0") }

dependencies {
    api(project(":contexts:notifications:application"))
    api(project(":contexts:tenantaccessgovernance:application"))
    implementation(project(":core:auth"))
    implementation(project(":core:network"))
    implementation(libs.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
