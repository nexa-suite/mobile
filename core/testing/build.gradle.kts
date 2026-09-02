plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "com.nexa.mobile.core.testing"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.junit)
    implementation(libs.kotlinx.coroutines.test)
}
