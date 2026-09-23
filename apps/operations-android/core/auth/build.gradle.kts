plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nexa.mobile.operations.core.auth"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
