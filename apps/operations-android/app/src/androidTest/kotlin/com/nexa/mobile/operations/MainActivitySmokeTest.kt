package com.nexa.mobile.operations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.R as AccessR
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun applicationGraphResolvesAndAccessProductScreenRenders() {
        composeRule.onNodeWithText(
            composeRule.activity.getString(AccessR.string.access_welcome_title)
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(AccessR.string.access_welcome_continue)
        ).performClick()
        composeRule.onNodeWithText(
            composeRule.activity.getString(AccessR.string.access_sign_in_title)
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(AccessR.string.access_identifier_label)
        ).assertIsDisplayed()
    }
}
