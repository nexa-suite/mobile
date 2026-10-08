plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin { jvmToolchain(17) }

ktlint { version.set("1.8.0") }

dependencies {
    api(project(":contexts:creditreceivables:domain"))
    api(project(":contexts:salescommitment:domain"))
    api(project(":contexts:tenantaccessgovernance:domain"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
