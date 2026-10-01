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

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs JVM unit tests for every configured Operations Android module."
    dependsOn(configuredAndroidModules.map { "${it.path}:testDebugUnitTest" })
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
                val body = source.readText().let {
                    if (source.name == "NexaCatalogGateway.kt") {
                        check(
                            listOf("POST", "PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) { "Catalog identification adapter must use protected reads only" }
                        it.replace("\"/api/v1/catalog-items\"", "\"<catalog-read-route>\"")
                    } else if (source.name == "NexaCommercialCatalogGateway.kt") {
                        check(it.contains("COMMERCIAL_CATALOG_PATH = \"/api/v1/client-accounts\"")) {
                            "Commercial catalog must use the canonical protected projection"
                        }
                        check(listOf("POST", "PUT", "PATCH", "DELETE").none { method ->
                            it.contains("ProtectedMethod.$method")
                        }) { "Commercial catalog transport must remain read-only" }
                        it.replace("/api/v1/catalog-items", "<commercial-catalog-read-route>")
                    } else if (source.name == "NexaDispatchReadinessGateway.kt") {
                        check(it.contains("READINESS_PATH = \"/api/v1/dispatch-readiness\"")) {
                            "Dispatch readiness must use the canonical protected projection"
                        }
                        check(listOf("POST", "PUT", "PATCH", "DELETE").none { method ->
                            it.contains("ProtectedMethod.$method")
                        }) { "Dispatch readiness transport must remain read-only" }
                        it.replace("/api/v1/dispatch-readiness", "<dispatch-readiness-read-route>")
                    } else if (source.name == "NexaSkuIdentifierGateway.kt") {
                        check(it.contains("SKU_RESOLUTION_PATH = \"/api/v1/skus/resolve\"")) {
                            "SKU resolver route must remain the exact approved endpoint"
                        }
                        check(it.contains("ProtectedMethod.GET")) {
                            "SKU resolver must remain a protected read"
                        }
                        check(
                            listOf("POST", "PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) { "SKU resolver adapter must not add Product mutations" }
                        it.replace("/api/v1/skus/resolve", "<sku-identifier-read-route>")
                    } else if (source.name == "NexaReceivingGateway.kt") {
                        check(
                            it.contains("WAREHOUSES_PATH = \"/api/v1/warehouses\"") &&
                                it.contains(
                                    "INBOUND_RECEIPTS_PATH = \"/api/v1/inventory/inbound-receipts\""
                                )
                        ) { "Receiving transport routes must remain the canonical endpoints" }
                        check(
                            listOf("PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) { "Receiving adapter must not add unrelated Product mutations" }
                        it.replace("\"/api/v1/warehouses\"", "\"<warehouse-lookup-route>\"")
                            .replace(
                                "\"/api/v1/inventory/inbound-receipts\"",
                                "\"<inbound-receipt-route>\""
                            )
                    } else if (source.name == "NexaPickingGateway.kt") {
                        check(it.contains("FULFILLMENTS_PATH = \"/api/v1/fulfillments\"")) {
                            "Picking transport must use the canonical fulfillment route"
                        }
                        check(
                            listOf("PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) { "Picking transport must not add unrelated mutations" }
                        check(
                            listOf("physical-allocation", "picking-starts", "picking-confirmations")
                                .all(it::contains)
                        ) { "Picking routes must remain canonical" }
                        it.replace("/api/v1/fulfillments", "<protected-picking-route>")
                    } else if (source.name == "NexaDispositionGateway.kt") {
                        check(it.contains("INVENTORY_LOTS_PATH = \"/api/v1/inventory/lots\"")) {
                            "Disposition must use current lot reads and typed dispositions"
                        }
                        check(it.contains("/dispositions")) {
                            "Disposition transport must use the canonical typed mutation"
                        }
                        check(listOf("PUT", "PATCH", "DELETE").none { method ->
                            it.contains("ProtectedMethod.$method")
                        }) { "Disposition must not add unrelated mutations" }
                        it.replace("/api/v1/inventory/lots", "<protected-lot-disposition-route>")
                    } else if (source.name == "NexaStockConditionGateway.kt") {
                        check(
                            it.contains("LOTS_PATH = \"/api/v1/inventory/lots\"") &&
                                it.contains(
                                    "WAREHOUSES_PATH = \"/api/v1/warehouses\""
                                ) &&
                                it.contains(
                                    "\"\$WAREHOUSES_PATH/\$warehouseId/inventory-availability\""
                                )
                        ) {
                            "Stock transport must use authorized lot and Warehouse availability routes"
                        }
                        check(
                            listOf("POST", "PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) { "Stock condition adapter must remain read-only" }
                        it.replace("/api/v1/inventory/lots", "<authorized-lot-read-route>")
                            .replace(
                                "/api/v1/warehouses",
                                "<authorized-warehouse-read-route>"
                            )
                            .replace(
                                "/inventory-availability",
                                "<warehouse-availability-read-route>"
                            )
                    } else {
                        it
                    }
                }
                listOf(
                    "/catalog",
                    "/skus",
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
            ":core:local",
            ":core:network",
            ":core:designsystem",
            ":core:device"
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

        val localBuild = file("core/local/build.gradle.kts").readText()
        val localSources = file("core/local/src/main").walkTopDown().filter {
            it.extension == "kt"
        }.toList()
        check(
            listOf(
                "project(\":core:auth\")",
                "project(\":core:network\")",
                "project(\":feature:",
                "room",
                "datastore",
                "work-runtime",
                "retrofit",
                "okhttp"
            ).none { localBuild.contains(it, ignoreCase = true) }
        ) {
            ":core:local must remain a scoped metadata foundation without transport or sync dependencies"
        }
        check(
            localSources.none { source ->
                val body = source.readText()
                listOf(
                    "com.nexa.mobile.operations.core.auth",
                    "com.nexa.mobile.operations.core.network",
                    "com.nexa.mobile.operations.feature.",
                    "ConfirmedReceivingProduct",
                    "ReceivedLotFacts",
                    "androidx.work.",
                    "androidx.room."
                ).any(body::contains)
            }
        ) { ":core:local must store plain scoped metadata, not authority or server facts" }

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
                    !buildText.contains("project(\":core:local\")") &&
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
                appBuild.contains("project(\":feature:warehouse\")") &&
                appBuild.contains("project(\":core:local\")")
        ) { ":app must compose access, warehouse, and scoped local metadata foundations" }

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

        val deviceBuild = file("core/device/build.gradle.kts").readText()
        val deviceSources = file("core/device/src/main").walkTopDown().filter {
            it.extension == "kt"
        }.toList()
        check(
            listOf("project(\":core:auth\")", "project(\":core:network\")", "project(\":feature:")
                .none(deviceBuild::contains)
        ) { ":core:device must remain independent of session, transport, and Product features" }
        check(
            deviceSources.none { source ->
                source.readText().contains("com.nexa.mobile.operations.core.network") ||
                    source.readText().contains("com.nexa.mobile.operations.feature.")
            }
        ) { ":core:device contains transport or Product feature state" }

        val warehouseBuild = file("feature/warehouse/build.gradle.kts").readText()
        check(warehouseBuild.contains("project(\":core:device\")")) {
            ":feature:warehouse must consume the typed scanner boundary"
        }

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
        listOf("app/src/main/AndroidManifest.xml", "app/src/release/AndroidManifest.xml")
            .map { file(it) }
            .filter { it.exists() }
            .forEach { manifest ->
                check(!manifest.readText().contains("ACCESS_LOCAL_NETWORK")) {
                    "Local fixture network permission must remain debug-only"
                }
            }
    }
}
