plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.nexa.mobile.operations.data"
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
    implementation(project(":core:auth"))
    implementation(project(":core:local"))
    implementation(project(":core:network"))
    implementation(project(":core:device"))
    implementation(project(":feature:access:contract"))
    implementation(project(":feature:warehouse:contract"))
    implementation(project(":feature:dispatch:contract"))
    implementation(project(":feature:delivery:contract"))
    implementation(project(":feature:commercial:contract"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
