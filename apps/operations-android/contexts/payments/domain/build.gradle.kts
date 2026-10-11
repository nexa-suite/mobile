plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin { jvmToolchain(17) }

ktlint { version.set("1.8.0") }

dependencies {
    testImplementation(libs.junit)
}
