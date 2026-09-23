plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint)
}

ktlint { version.set("1.8.0") }

tasks.named("ktlintCheck") {
    dependsOn(subprojects.filter { it.path != ":core" }.map { "${it.path}:ktlintCheck" })
}

tasks.named("ktlintFormat") {
    dependsOn(subprojects.filter { it.path != ":core" }.map { "${it.path}:ktlintFormat" })
}

tasks.register("lintDebug") {
    dependsOn(subprojects.filter { it.path != ":core" }.map { "${it.path}:lintDebug" })
}

tasks.register("verifyAndroidArchitecture") {
    group = "verification"
    description = "Verifies the Operations Android foundation module boundaries."
    doLast {
        val authBuild = file("core/auth/build.gradle.kts").readText()
        val authSources = file("core/auth/src/main").walkTopDown().filter {
            it.extension == "kt"
        }.toList()
        check(
            !authBuild.contains("project(\":core:network\")") &&
                !authBuild.contains("libs.retrofit") && !authBuild.contains("libs.okhttp")
        ) {
            "core:auth must remain independent of the HTTP implementation"
        }
        check(
            authSources.none { source ->
                val body = source.readText()
                body.contains("import retrofit2.") || body.contains("import okhttp3.") ||
                    body.contains("com.nexa.mobile.operations.core.network")
            }
        ) { "core:auth source imports a network implementation" }
        val presentation = listOf("MainActivity.kt", "RootViewModel.kt", "RootNavigation.kt")
            .map { file("app/src/main/kotlin/com/nexa/mobile/operations/$it") }
        check(
            presentation.none { source ->
                val body = source.readText()
                body.contains("import retrofit2.") || body.contains("import okhttp3.") ||
                    body.contains("com.nexa.mobile.operations.core.network")
            }
        ) { "Root presentation imports transport implementation" }
        val networkSources = file("core/network/src/main").walkTopDown().filter {
            it.extension ==
                "kt"
        }.toList()
        check(
            networkSources.none { source ->
                val body = source.readText()
                listOf(
                    "/catalog",
                    "/inventory",
                    "/warehouse",
                    "/dispatch",
                    "/delivery",
                    "hasPermission(",
                    "roles.contains("
                )
                    .any(body::contains)
            }
        ) { "core:network contains Product routes or client authorization decisions" }
    }
}
