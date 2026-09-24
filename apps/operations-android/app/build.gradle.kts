import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
}

val debugApiBaseUrl = providers.gradleProperty(
    "nexaDebugApiBaseUrl"
).orElse("http://10.0.2.2:8080/").get()
val releaseApiBaseUrl = providers.gradleProperty("nexaReleaseApiBaseUrl").orNull.orEmpty()
fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.nexa.mobile.operations"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nexa.mobile.operations"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "API_BASE_URL", debugApiBaseUrl.asBuildConfigString())
        }
        getByName("release") {
            buildConfigField("String", "API_BASE_URL", releaseApiBaseUrl.asBuildConfigString())
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        // These versions are frozen by the accepted Android foundation baseline.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

ktlint { version.set("1.8.0") }

val validateReleaseEndpoint = tasks.register("validateReleaseEndpoint") {
    doLast {
        val url = releaseApiBaseUrl
        require(url.isNotBlank()) {
            "Release API base URL must be supplied as nexaReleaseApiBaseUrl"
        }
        val parsed = URI(url)
        val host = parsed.host?.lowercase()?.trimEnd('.').orEmpty()
        val exampleDomains = setOf("example.com", "example.net", "example.org")
        require(
            parsed.scheme == "https" && host.isNotBlank() && parsed.rawUserInfo == null &&
                (parsed.rawPath.isNullOrEmpty() || parsed.rawPath == "/") &&
                parsed.rawQuery == null &&
                parsed.rawFragment == null &&
                host !in setOf("localhost", "127.0.0.1", "10.0.2.2", "0.0.0.0", "api.nexa.com") &&
                !host.endsWith(".localhost") && !host.endsWith(".local") &&
                !host.endsWith(".test") && !host.endsWith(".invalid") &&
                !host.endsWith(".example") &&
                exampleDomains.none { host == it || host.endsWith(".$it") } &&
                !host.startsWith("10.") && !host.startsWith("192.168.") &&
                !Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\.").containsMatchIn(host)
        ) {
            "Release API base URL must be a non-local HTTPS origin"
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseEndpoint) }

dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:network"))
    implementation(project(":core:designsystem"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.hilt.android)
    implementation(libs.okhttp)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
