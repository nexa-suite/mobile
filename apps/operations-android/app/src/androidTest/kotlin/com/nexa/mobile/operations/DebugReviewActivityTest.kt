package com.nexa.mobile.operations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugReviewActivityTest {
    @get:Rule val composeRule = createAndroidComposeRule<DebugReviewActivity>()

    @Test
    fun scenarioPickerUsesTheProductionConfirmedSkuScreen() {
        composeRule.onNodeWithText("Revisión local · datos sintéticos · sin autoridad del servidor")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Escenarios de revisión").performClick()
        composeRule.onNodeWithTag("review-scenario-picker")
            .performScrollToNode(hasText("SKU · confirmación recibida"))
        composeRule.onNodeWithText("SKU · confirmación recibida").performClick()

        composeRule.onNodeWithText("Identificación confirmada").assertIsDisplayed()
        composeRule.onNodeWithText("SKU-DEMO-001").assertIsDisplayed()
        composeRule.onNodeWithText("No se realizó ninguna operación de inventario.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
