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

val configuredAndroidModules = subprojects.filter { it.buildFile.isFile }

tasks.named("ktlintCheck") {
    dependsOn(configuredAndroidModules.map { "${it.path}:ktlintCheck" })
}

tasks.named("ktlintFormat") {
    dependsOn(configuredAndroidModules.map { "${it.path}:ktlintFormat" })
}

tasks.register("lintDebug") {
    dependsOn(configuredAndroidModules.map { "${it.path}:lintDebug" })
}

tasks.register("verifyAndroidArchitecture") {
    group = "verification"
    description =
        "Verifies the Operations Android foundation and accepted Product module boundaries."
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

        val settings = file("settings.gradle.kts").readText()
        val includedModules = Regex("\"(:[^\"\\n]+)\"")
            .findAll(settings.substringAfter("include("))
            .map { it.groupValues[1] }
            .toSet()
        val foundationModules = setOf(
            ":app",
            ":core:auth",
            ":core:network",
            ":core:designsystem"
        )
        val requiredFeatureModules = setOf(":feature:access", ":feature:warehouse")
        val allowedFeatureModules = setOf(
            ":feature:access",
            ":feature:warehouse",
            ":feature:dispatch",
            ":feature:delivery"
        )
        check(
            includedModules.containsAll(foundationModules + requiredFeatureModules) &&
                includedModules.all { it in foundationModules || it in allowedFeatureModules }
        ) {
            "Operations Android modules must use the foundations and Blueprint feature areas"
        }

        val featureModules = includedModules
            .filter { it.startsWith(":feature:") }
            .map { it.removePrefix(":").replace(':', '/') }
        featureModules.forEach { module ->
            val buildFile = file("$module/build.gradle.kts")
            val sources = file("$module/src/main").walkTopDown().filter {
                it.extension == "kt"
            }.toList()
            val buildText = buildFile.readText()
            check(
                !buildText.contains("project(\":core:auth\")") &&
                    !buildText.contains("project(\":core:network\")") &&
                    !buildText.contains("project(\":feature:")
            ) { "$module must remain independent of auth, transport, and other Product features" }
            check(
                listOf("room", "datastore", "work-runtime", "camera", "retrofit", "okhttp")
                    .none { buildText.contains(it, ignoreCase = true) }
            ) {
                "$module added a forbidden persistence, background, device, or transport dependency"
            }
            check(
                sources.none { source ->
                    val body = source.readText()
                    listOf(
                        "import retrofit2.",
                        "import okhttp3.",
                        "com.nexa.mobile.operations.core.network",
                        "com.nexa.mobile.operations.core.auth",
                        "workspaceSlug",
                        "NativeSignIn"
                    ).any(body::contains)
                }
            ) { "$module source crosses a transport/security boundary or adds slug identity UX" }
        }

        val appBuild = file("app/build.gradle.kts").readText()
        check(
            appBuild.contains("project(\":feature:access\")") &&
                appBuild.contains("project(\":feature:warehouse\")")
        ) { ":app must compose the existing access and warehouse feature areas" }

        val designBuild = file("core/designsystem/build.gradle.kts").readText()
        val designSources = file("core/designsystem/src/main").walkTopDown().filter {
            it.extension == "kt"
        }.toList()
        check(!designBuild.contains("project(\":feature:")) {
            ":core:designsystem must not depend on Product workflow modules"
        }
        check(
            designSources.none { source ->
                source.readText().contains("com.nexa.mobile.operations.feature.")
            }
        ) { ":core:designsystem contains Product workflow state" }

        val appSources = file("app/src/main").walkTopDown().filter { it.extension == "kt" }.toList()
        check(
            appSources.none { source ->
                val body = source.readText()
                body.contains("DebugReviewActivity") || body.contains("ReviewScenarioProvider")
            }
        ) { "Debug review tooling leaked into release sources" }
        check(
            !file("app/src/main/AndroidManifest.xml").readText().contains("DebugReviewActivity")
        ) {
            "Debug review Activity must not appear in the release manifest"
        }
    }
}
