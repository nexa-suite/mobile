package com.nexa.mobile.operations

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.ReceivingCommandStatus
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.ReceivingViewModel
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.StockConditionAvailabilityStatus
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.StockConditionDetailStatus
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.StockConditionStatus
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.StockConditionViewModel
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessViewModel
import com.nexa.mobile.operations.workentry.WarehouseViewModel
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveCandidateIdentityIntegrationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun nativeIdentityAndCatalogDetailConfirmSkuAgainstCandidateApi() {
        val arguments = InstrumentationRegistry.getArguments()
        val identifier = arguments.getString("nexaLiveIdentifier")
        val password = arguments.getString("nexaLivePassword")
        val query = arguments.getString("nexaLiveQuery")
        val expectedSku = arguments.getString("nexaLiveSku")
        val captureEnabled = arguments.getString("nexaLiveCapture") == "true"
        assumeTrue(
            "candidate credentials are needed for the opt-in live test",
            !identifier.isNullOrBlank() && !password.isNullOrBlank() &&
                !query.isNullOrBlank() && !expectedSku.isNullOrBlank()
        )

        // The debug fixture reaches the host API through the emulator's local network.
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                composeRule.activity.packageName,
                Manifest.permission.ACCESS_LOCAL_NETWORK
            )
            assertTrue(
                "local fixture network permission must be granted",
                composeRule.activity.checkSelfPermission(
                    Manifest.permission.ACCESS_LOCAL_NETWORK
                ) ==
                    PackageManager.PERMISSION_GRANTED
            )
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
                .fetchSemanticsNodes().size == 2
        }
        val identityFields = composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        identityFields.assertCountEquals(2)
        composeRule.onNodeWithText("Identificador").assertIsDisplayed()
        composeRule.onNodeWithText("Contraseña").assertIsDisplayed()
        identityFields[0].performTextInput(identifier!!)
        identityFields[1].performTextInput(password!!)
        identityFields[1].performImeAction()
        Espresso.closeSoftKeyboard()
        val form = ViewModelProvider(composeRule.activity)[AccessViewModel::class.java].state.value
        assertTrue(
            "native identity field must update the access model",
            form.identifier == identifier
        )
        assertTrue("native password field must update the access model", form.password == password)
        composeRule.onNodeWithText("Iniciar sesión").performScrollTo().performClick()

        try {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodes(
                    hasContentDescription("Contexto actual:", substring = true),
                    useUnmergedTree = true
                ).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            val access = ViewModelProvider(composeRule.activity)[AccessViewModel::class.java]
                .state.value
            val session = ViewModelProvider(composeRule.activity)[RootViewModel::class.java]
                .state.value
            val visibleStates = listOf(
                AccessResources.string.access_sign_in_title,
                AccessResources.string.access_sign_in_loading_title,
                AccessResources.string.access_local_error_title,
                AccessResources.string.access_no_context_title,
                AccessResources.string.access_notice_auth_rejected,
                AccessResources.string.access_notice_network,
                AccessResources.string.access_notice_service,
                AccessResources.string.access_notice_context_unknown,
                AccessResources.string.access_identifier_error,
                AccessResources.string.access_password_error,
                AccessResources.string.context_initial_title
            ).filter { resource ->
                composeRule.onAllNodes(hasText(composeRule.activity.getString(resource)))
                    .fetchSemanticsNodes().isNotEmpty()
            }.map(composeRule.activity.resources::getResourceEntryName)
            throw AssertionError(
                "Native work entry unavailable; session=$session, " +
                    "access=${access.stage}/${access.notice}, " +
                    "confirmedContext=${access.activeContext != null}, " +
                    "identifierError=${access.identifierError}, " +
                    "passwordError=${access.passwordError}; " +
                    "visible states: $visibleStates",
                timeout
            )
        }
        composeRule.onNodeWithText("Identificar producto").assertIsDisplayed()
        captureScreenWhenEnabled(captureEnabled, "01-warehouse-home-before-query.png")

        composeRule.onNodeWithText("Identificar producto").performClick()
        composeRule.onNode(hasSetTextAction() and hasText("Buscar producto"))
            .performTextInput(query!!)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithText("Buscar", substring = false).performScrollTo().performClick()
        val candidateMatcher = hasClickAction() and !hasSetTextAction() and
            hasText(expectedSku!!, substring = true)
        try {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodes(candidateMatcher)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            val warehouse = ViewModelProvider(composeRule.activity)[WarehouseViewModel::class.java]
                .state.value
            throw AssertionError(
                "Native Catalog candidate unavailable; route=${warehouse.route}, " +
                    "searchStatus=${warehouse.search?.status}, " +
                    "searchError=${warehouse.search?.errorMessage}, " +
                    "candidates=${warehouse.search?.candidates?.size}, " +
                    "confirmed=${warehouse.confirmedSku != null}",
                timeout
            )
        }
        composeRule.onNodeWithText("Identificación confirmada").assertDoesNotExist()
        composeRule.onNode(candidateMatcher).performScrollTo().assertIsDisplayed().performClick()
        try {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodes(hasText("Identificación confirmada"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            val warehouse = ViewModelProvider(composeRule.activity)[WarehouseViewModel::class.java]
                .state.value
            throw AssertionError(
                "Native Catalog detail unavailable; route=${warehouse.route}, " +
                    "searchStatus=${warehouse.search?.status}, " +
                    "searchError=${warehouse.search?.errorMessage}, " +
                    "confirmed=${warehouse.confirmedSku != null}",
                timeout
            )
        }
        composeRule.onNodeWithText(expectedSku, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("No se realizó ninguna operación de inventario.")
            .performScrollTo().assertIsDisplayed()
        captureScreenWhenEnabled(captureEnabled, "02-catalog-detail-no-inventory.png")

        val accessModel = ViewModelProvider(composeRule.activity)[AccessViewModel::class.java]
        val warehouseModel = ViewModelProvider(composeRule.activity)[WarehouseViewModel::class.java]
        val priorEpoch = accessModel.state.value.authorityEpoch
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            accessModel.state.value.authorityEpoch > priorEpoch &&
                composeRule.onAllNodes(hasText("Identificar producto"))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Identificación confirmada").assertDoesNotExist()
        composeRule.runOnIdle {
            assertNull(
                "Foreground return must discard prior confirmed identity",
                warehouseModel.state.value.confirmedSku
            )
            assertTrue(
                "Foreground return must use fresh authority",
                accessModel.state.value.authorityEpoch > priorEpoch
            )
        }

        if (arguments.getString("nexaLiveReceiving") == "true") {
            val receivingWarehouseId = checkNotNull(
                arguments.getString("nexaLiveWarehouseId")
            ) { "The live runner must provide its API-authorized receiving warehouse." }
            val receivingZoneId = checkNotNull(
                arguments.getString("nexaLiveZoneId")
            ) { "The live runner must provide its API-authorized receiving zone." }
            val temperatureReading = checkNotNull(
                arguments.getString("nexaLiveTemperature")
            ) { "The live runner must provide a reading within the authoritative range." }
            composeRule.onNode(hasClickAction() and hasText("Registrar llegada"))
                .performScrollTo().performClick()
            composeRule.onNodeWithText("Elegir producto").performScrollTo().performClick()
            composeRule.onNode(hasSetTextAction() and hasText("Buscar producto"))
                .performTextInput(query)
            Espresso.closeSoftKeyboard()
            composeRule.onNodeWithText("Buscar", substring = false).performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodes(candidateMatcher).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(candidateMatcher).performScrollTo().performClick()
            val receivingModel = ViewModelProvider(
                composeRule.activity
            )[ReceivingViewModel::class.java]
            composeRule.waitUntil(timeoutMillis = 15_000) {
                receivingModel.state.value.product != null &&
                    receivingModel.state.value.warehouses.isNotEmpty()
            }
            val warehouse = receivingModel.state.value.warehouses.singleOrNull {
                it.id == receivingWarehouseId
            } ?: throw AssertionError(
                "API-authorized warehouse is absent from the current Receiving choices."
            )
            composeRule.onNodeWithText("${warehouse.name} · ${warehouse.code}")
                .performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                receivingModel.state.value.zones.isNotEmpty()
            }
            val zone = receivingModel.state.value.zones.singleOrNull {
                it.id == receivingZoneId && it.warehouseId == warehouse.id
            } ?: throw AssertionError(
                "API-authorized zone is absent from the selected warehouse's current choices."
            )
            composeRule.onNodeWithText("${zone.name} · ${zone.code}")
                .performScrollTo().performClick()
            val batch = "ANDROID-W4-${System.currentTimeMillis()}"
            fun fill(label: String, value: String) {
                val field = composeRule.onNode(hasSetTextAction() and hasText(label))
                field.performScrollTo()
                field.performTextClearance()
                field.performTextInput(value)
                Espresso.closeSoftKeyboard()
            }
            fill("Lote", batch)
            fill("Fecha de vencimiento", "2099-01-01")
            fill("Cantidad recibida", "1.25")
            fill("Unidad", receivingModel.state.value.product!!.unit.ifBlank { "UNIT" })
            fill(
                composeRule.activity.getString(
                    WarehouseResources.string.receiving_temperature_label
                ),
                temperatureReading
            )
            composeRule.onNode(hasClickAction() and hasText("Registrar llegada"))
                .performScrollTo().performClick()
            try {
                composeRule.waitUntil(timeoutMillis = 20_000) {
                    receivingModel.state.value.command == ReceivingCommandStatus.Confirmed
                }
            } catch (timeout: ComposeTimeoutException) {
                val receiving = receivingModel.state.value
                val rejectionCode = receiving.rejectionCode?.let { code ->
                    if (Regex("[A-Z0-9_]{1,80}").matches(code)) code else "redacted"
                } ?: "none"
                throw AssertionError(
                    "Native Receiving submit not confirmed; command=${receiving.command}, " +
                        "notice=${receiving.notice}, rejectionCode=$rejectionCode, " +
                        "validationError=${receiving.validationError}, " +
                        "warehouseLookup=${receiving.warehouseLookup}, " +
                        "zoneLookup=${receiving.zoneLookup}, " +
                        "productSelected=${receiving.product != null}, " +
                        "productVerified=${receiving.productVerifiedEpoch != null}, " +
                        "warehouseSelected=${receiving.selectedWarehouseId != null}, " +
                        "zoneSelected=${receiving.selectedZoneId != null}, " +
                        "metadata=${receiving.metadata}, confirmed=${receiving.confirmedLot != null}",
                    timeout
                )
            }
            composeRule.onNodeWithText(
                "Lote confirmado por Nexa"
            ).performScrollTo().assertIsDisplayed()
            val lot = receivingModel.state.value.confirmedLot!!
            assertTrue(
                "server must confirm the exact submitted Warehouse",
                lot.warehouseId == warehouse.id
            )
            assertTrue(
                "server must confirm exact received quantity",
                lot.onHand.compareTo(java.math.BigDecimal("1.25")) == 0
            )
            assertTrue("server must confirm exact submitted batch", lot.batchNumber == batch)
            captureScreenWhenEnabled(captureEnabled, "03-receiving-confirmed.png")
            composeRule.onNodeWithText("Volver").performScrollTo().performClick()
            composeRule.onNode(hasClickAction() and hasText("Estado de existencias"))
                .performScrollTo().performClick()
            val stockModel = ViewModelProvider(
                composeRule.activity
            )[StockConditionViewModel::class.java]
            composeRule.waitUntil(timeoutMillis = 15_000) {
                stockModel.state.value.status == StockConditionStatus.Current &&
                    stockModel.state.value.lots.any { it.id == lot.id }
            }
            val lotIndex = stockModel.state.value.lots.indexOfFirst { it.id == lot.id }
            assertTrue("received lot remains in current list", lotIndex >= 0)
            composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(4 + lotIndex)
            composeRule.onNode(hasClickAction() and hasText(batch, substring = true))
                .assertIsDisplayed().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                stockModel.state.value.detailStatus == StockConditionDetailStatus.Current &&
                    stockModel.state.value.availabilityStatus ==
                    StockConditionAvailabilityStatus.Current
            }
            assertTrue(
                "stock detail matches receipt lot",
                stockModel.state.value.selectedLot?.id == lot.id
            )
            assertTrue(
                "stock Warehouse matches authorized receipt",
                stockModel.state.value.selectedLot?.warehouseId == warehouse.id
            )
            assertTrue(
                "sellable quantity supplied by server",
                stockModel.state.value.availability != null
            )
            captureScreenWhenEnabled(captureEnabled, "04-stock-detail-confirmed.png")
        }
    }

    private fun captureScreenWhenEnabled(enabled: Boolean, fileName: String) {
        if (!enabled) return

        composeRule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val captureDirectory = composeRule.activity.getExternalFilesDir("wireflow-captures")
            ?: throw AssertionError("External files directory is unavailable for captures.")
        assertTrue(
            "capture directory must be available",
            captureDirectory.isDirectory || captureDirectory.mkdirs()
        )
        val outputFile = java.io.File(captureDirectory, fileName)
        val compressed = try {
            outputFile.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue(
            "enabled capture must produce a PNG: ${outputFile.absolutePath}",
            compressed && outputFile.isFile && outputFile.length() > 0
        )
    }
}
