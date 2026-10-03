package com.nexa.mobile.operations

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexa.mobile.operations.core.designsystem.OperationsTheme
import com.nexa.mobile.operations.feature.warehouse.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.ProductScannerScreen
import com.nexa.mobile.operations.feature.warehouse.ProductScannerUiState
import com.nexa.mobile.operations.feature.warehouse.ScannerUnverifiedReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerScreensUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun deniedCameraKeepsManualIdentificationAndSettingsAvailable() {
        var manualSelections = 0
        var settingsSelections = 0
        composeRule.setContent {
            OperationsTheme {
                ProductScannerScreen(
                    state = ProductScannerUiState.PermissionPermanentlyDenied(7),
                    activeContext = ActiveOperationsContext("Company", "Workspace", 7),
                    cameraPreview = { error("Denied permission must not bind camera") },
                    onBack = {},
                    onChangeContext = {},
                    onRequestPermission = { error("Permanent denial uses settings") },
                    onOpenSettings = { settingsSelections++ },
                    onRetryScan = {},
                    onManualSearch = { manualSelections++ }
                )
            }
        }
        composeRule.onNodeWithText("No se permitió la cámara").assertIsDisplayed()
        composeRule.onNodeWithText("Abrir ajustes").performScrollTo().performClick()
        composeRule.onNodeWithText("Buscar manualmente").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, settingsSelections)
            assertEquals(1, manualSelections)
        }
    }

    @Test fun resolvingAndAmbiguousCodeNeverDisplayConfirmedIdentity() {
        val state = mutableStateOf<ProductScannerUiState>(ProductScannerUiState.Resolving(7))
        composeRule.setContent {
            OperationsTheme {
                ProductScannerScreen(
                    state = state.value,
                    activeContext = ActiveOperationsContext("Company", "Workspace", 7),
                    cameraPreview = { error("Resolution must release preview") },
                    onBack = {},
                    onChangeContext = {},
                    onRequestPermission = {},
                    onOpenSettings = {},
                    onRetryScan = {},
                    onManualSearch = {}
                )
            }
        }
        composeRule.onNodeWithText("Comprobando código").assertIsDisplayed()
        composeRule.onNodeWithText("Identificación confirmada").assertDoesNotExist()
        composeRule.runOnIdle {
            state.value = ProductScannerUiState.Unverified(
                7,
                ScannerUnverifiedReason.AmbiguousCode,
                2
            )
        }
        composeRule.onNodeWithText("Identificación confirmada").assertDoesNotExist()
        composeRule.onNodeWithText("Buscar manualmente").performScrollTo().assertIsDisplayed()
    }
}
