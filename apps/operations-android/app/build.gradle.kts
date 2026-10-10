import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
        versionCode = 11
        versionName = "1.2.0"
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
    implementation(project(":contexts:tenantaccessgovernance:domain"))
    implementation(project(":contexts:tenantaccessgovernance:application"))
    implementation(project(":contexts:tenantaccessgovernance:infrastructure"))
    implementation(project(":contexts:tenantaccessgovernance:presentation"))
    implementation(project(":contexts:customerbuyerrelationships:domain"))
    implementation(project(":contexts:customerbuyerrelationships:application"))
    implementation(project(":contexts:customerbuyerrelationships:infrastructure"))
    implementation(project(":contexts:customerbuyerrelationships:presentation"))
    implementation(project(":contexts:catalogcommercialpolicy:domain"))
    implementation(project(":contexts:catalogcommercialpolicy:application"))
    implementation(project(":contexts:catalogcommercialpolicy:infrastructure"))
    implementation(project(":contexts:catalogcommercialpolicy:presentation"))
    implementation(project(":contexts:salescommitment:domain"))
    implementation(project(":contexts:salescommitment:application"))
    implementation(project(":contexts:salescommitment:infrastructure"))
    implementation(project(":contexts:salescommitment:presentation"))
    implementation(project(":contexts:inventoryavailability:domain"))
    implementation(project(":contexts:inventoryavailability:application"))
    implementation(project(":contexts:inventoryavailability:infrastructure"))
    implementation(project(":contexts:inventoryavailability:presentation"))
    implementation(project(":contexts:fulfillmentdelivery:domain"))
    implementation(project(":contexts:fulfillmentdelivery:application"))
    implementation(project(":contexts:fulfillmentdelivery:infrastructure"))
    implementation(project(":contexts:fulfillmentdelivery:presentation"))
    implementation(project(":contexts:creditreceivables:domain"))
    implementation(project(":contexts:creditreceivables:application"))
    implementation(project(":contexts:creditreceivables:infrastructure"))
    implementation(project(":contexts:businessdocuments:domain"))
    implementation(project(":contexts:businessdocuments:application"))
    implementation(project(":contexts:businessdocuments:infrastructure"))
    implementation(project(":contexts:businessdocuments:presentation"))
    implementation(project(":contexts:notifications:application"))
    implementation(project(":contexts:notifications:infrastructure"))
    implementation(project(":contexts:notifications:presentation"))

    implementation(project(":core:auth"))
    implementation(project(":core:local"))
    implementation(project(":core:network"))
    implementation(project(":core:device"))
    implementation(project(":core:designsystem"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.camera.view)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.hilt.android)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
