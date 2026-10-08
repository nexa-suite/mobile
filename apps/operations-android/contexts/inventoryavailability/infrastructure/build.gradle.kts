plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.nexa.mobile.operations.inventoryavailability.infrastructure"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
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
    api(project(":contexts:fulfillmentdelivery:application"))
    api(project(":contexts:inventoryavailability:application"))
    api(project(":contexts:inventoryavailability:domain"))
    api(project(":contexts:tenantaccessgovernance:domain"))
    implementation(project(":core:auth"))
    implementation(project(":core:local"))
    implementation(project(":core:network"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.hilt.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.converter)
    implementation(libs.okhttp)
    ksp(libs.hilt.compiler)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
