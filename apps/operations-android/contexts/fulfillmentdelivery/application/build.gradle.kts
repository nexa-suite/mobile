plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin { jvmToolchain(17) }

ktlint { version.set("1.8.0") }

dependencies {
    api(project(":contexts:fulfillmentdelivery:domain"))
    api(project(":contexts:inventoryavailability:application"))
    api(project(":contexts:tenantaccessgovernance:application"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
