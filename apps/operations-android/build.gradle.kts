plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint)
}

ktlint { version.set("1.8.0") }

val configuredModules = subprojects.filter { it.buildFile.isFile }
val configuredAndroidModules = configuredModules.filter {
    !it.path.endsWith(":domain") && !it.path.endsWith(":application")
}
val configuredContractModules = configuredModules - configuredAndroidModules.toSet()

tasks.named("ktlintCheck") {
    dependsOn(configuredModules.map { "${it.path}:ktlintCheck" })
}

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs JVM unit tests for every configured Operations Android module."
    dependsOn(configuredAndroidModules.map { "${it.path}:testDebugUnitTest" })
    dependsOn(configuredContractModules.map { "${it.path}:test" })
}

tasks.named("ktlintFormat") {
    dependsOn(configuredModules.map { "${it.path}:ktlintFormat" })
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
        val networkSources = (
            listOf(file("core/network/src/main")) +
                file("contexts").listFiles().orEmpty().map {
                    it.resolve("infrastructure/src/main")
                }
            ).flatMap { it.walkTopDown().toList() }.filter {
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
                    } else if (source.name == "NexaOperationsCatalogGateway.kt") {
                        check(
                            it.contains(
                                "OPERATIONS_CATALOG_PATH = \"/api/v1/catalog/products\""
                            )
                        ) { "Operations catalog must use the authorized management projection" }
                        check(
                            listOf("POST", "PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) { "Operations catalog transport must remain read-only" }
                        it.replace(
                            "\"/api/v1/catalog/products\"",
                            "\"<operations-catalog-read-route>\""
                        )
                    } else if (source.name == "NexaCommercialCatalogGateway.kt") {
                        check(
                            it.contains("COMMERCIAL_CATALOG_PATH = \"/api/v1/client-accounts\"")
                        ) {
                            "Commercial catalog must use the canonical protected projection"
                        }
                        check(
                            listOf("POST", "PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) { "Commercial catalog transport must remain read-only" }
                        check(it.contains("/catalog-offers/")) {
                            "Commercial prices must come from the customer-specific offer projection"
                        }
                        it.replace("/catalog-offers/", "<customer-catalog-offer-read-route>")
                    } else if (source.name == "NexaDispatchReadinessGateway.kt") {
                        check(it.contains("READINESS_PATH = \"/api/v1/dispatch-readiness\"")) {
                            "Dispatch readiness must use the canonical protected projection"
                        }
                        check(
                            listOf("POST", "PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) { "Dispatch readiness transport must remain read-only" }
                        it.replace("/api/v1/dispatch-readiness", "<dispatch-readiness-read-route>")
                    } else if (source.name == "NexaDispatchAssignmentGateway.kt") {
                        check(
                            it.contains("DISPATCH_ASSIGNEES_PATH = \"/api/v1/dispatch-assignees\"")
                        ) {
                            "Dispatch assignment must use current authorized assignee projection"
                        }
                        check(
                            listOf("PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) {
                            "Assignment transport must preserve append-only plan changes"
                        }
                        it.replace("/api/v1/dispatch-assignees", "<authorized-assignee-read-route>")
                    } else if (source.name == "NexaFulfillmentDispatchGateway.kt") {
                        check(it.contains("FULFILLMENT_DISPATCH_BASE = \"/api/v1/fulfillments\"")) {
                            "Warehouse departure must use the canonical Fulfillment transition"
                        }
                        check(
                            listOf("PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) {
                            "Warehouse departure transport must not introduce unrelated mutations"
                        }
                        it.replace("/dispatches", "<warehouse-departure-route>")
                    } else if (source.name == "NexaDeliveryLoadGateway.kt") {
                        check(
                            listOf(
                                "/api/v1/dispatch/loads",
                                "/api/v1/driver/loads",
                                "/dispatch-window-plans"
                            ).all(it::contains)
                        ) {
                            "Load transport must preserve canonical planning and assigned Driver routes"
                        }
                        check(
                            listOf("PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) {
                            "Load transport must not add unrelated mutations"
                        }
                        check(
                            it.filterNot(Char::isWhitespace).contains(
                                "if(action==DeliveryLoadNetworkAction.REORDER){ProtectedMethod.PUT}else{ProtectedMethod.POST}"
                            )
                        ) {
                            "PUT is limited to explicit stop reorder; other load transitions remain POST"
                        }
                        it.replace("/api/v1/dispatch/loads", "<protected-load-planning-route>")
                            .replace("/dispatch-window-plans", "<missing-window-planning-route>")
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
                    } else if (source.name == "NexaStockTransferGateway.kt") {
                        check(
                            it.contains(
                                "INVENTORY_TRANSFERS_PATH = \"/api/v1/inventory/transfers\""
                            )
                        ) {
                            "Stock transfers must use the canonical inventory transfer route"
                        }
                        check(
                            listOf("PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) { "Stock transfer transport must not add unrelated mutations" }
                        it.replace(
                            "/api/v1/inventory/transfers",
                            "<protected-stock-transfer-route>"
                        )
                    } else if (source.name == "NexaLotSubstitutionGateway.kt") {
                        check(
                            it.contains(
                                "/api/v1/inventory/physical-allocation-substitution-requests"
                            )
                        ) {
                            "Lot substitution must use the canonical request-only inventory seam"
                        }
                        check(
                            listOf("PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) {
                            "Lot substitution transport must not mutate stock or allocation directly"
                        }
                        it.replace(
                            "/api/v1/inventory/physical-allocation-substitution-requests",
                            "<protected-lot-substitution-route>"
                        )
                    } else if (source.name == "NexaInboundDiscrepancyGateway.kt") {
                        check(
                            it.contains(
                                "INBOUND_CASES_PATH = \"/api/v1/inventory/inbound-discrepancy-cases\""
                            )
                        ) {
                            "Receiving discrepancies must use the canonical observation-case seam"
                        }
                        check(
                            listOf("PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) { "Receiving evidence must not introduce stock or approval mutations" }
                        it.replace(
                            "/api/v1/inventory/inbound-discrepancy-cases",
                            "<protected-inbound-case-route>"
                        )
                    } else if (source.name == "NexaDispatchHandoffIdentityGateway.kt") {
                        check(
                            it.contains(
                                "DISPATCH_HANDOFF_VALIDATE_PATH = \"/api/v1/delivery-handoff/validations\""
                            )
                        ) {
                            "Dispatch identity must use canonical bounded handoff validation"
                        }
                        check(
                            listOf("PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) {
                            "Handoff identity must not introduce outcome or receipt mutations"
                        }
                        it.replace(
                            "/api/v1/delivery-handoff/validations",
                            "<bounded-handoff-validation-route>"
                        )
                    } else if (source.name == "NexaCycleCountGateway.kt") {
                        check(
                            listOf(
                                "/api/v1/inventory/lots",
                                "/api/v1/inventory/cycle-counts",
                                "/corrections"
                            ).all(it::contains)
                        ) {
                            "Cycle counts must use scoped lot observations and explicit correction requests"
                        }
                        check(
                            listOf("PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) { "Cycle counts must not add unrelated stock mutations" }
                        it.replace("/api/v1/inventory", "<protected-cycle-count-route>")
                    } else if (source.name == "NexaDispositionGateway.kt") {
                        check(it.contains("INVENTORY_LOTS_PATH = \"/api/v1/inventory/lots\"")) {
                            "Disposition must use current lot reads and typed dispositions"
                        }
                        check(it.contains("/dispositions")) {
                            "Disposition transport must use the canonical typed mutation"
                        }
                        check(
                            listOf("PUT", "PATCH", "DELETE").none { method ->
                                it.contains("ProtectedMethod.$method")
                            }
                        ) { "Disposition must not add unrelated mutations" }
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

        val result = providers.exec {
            workingDir(rootDir)
            commandLine("python3", "scripts/verify-context-architecture.py")
        }.result.get()
        check(result.exitValue == 0) { "Context architecture verification failed" }
    }
}
