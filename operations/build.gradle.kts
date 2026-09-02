plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val nativeApiBaseUrl = providers.gradleProperty("nexaApiBaseUrl").orElse("").get().trim()
val nativeApiSurface = providers.gradleProperty("nexaApiSurface").orElse("").get().trim()

fun buildConfigString(value: String): String {
    require('\n' !in value && '\r' !in value) { "Native access build properties must not contain line breaks" }
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

android {
    namespace = "com.nexa.mobile.operations"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexa.mobile.operations"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0-dev.1"

        // Blank values fail closed. A native surface is never inferred from the app name.
        buildConfigField("String", "NEXA_API_BASE_URL", buildConfigString(nativeApiBaseUrl))
        buildConfigField("String", "NEXA_API_SURFACE", buildConfigString(nativeApiSurface))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(project(":core:network"))
    implementation(project(":core:storage"))
    implementation(project(":feature:access"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    testImplementation(libs.junit)
}
