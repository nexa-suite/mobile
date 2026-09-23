package com.nexa.mobile.operations

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.designsystem.OperationsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootNavigationSecurityTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun logoutReplacesProtectedTechnicalRootWithoutSavedHistory() {
        val state = mutableStateOf<SessionState>(SessionState.Active)
        composeRule.setContent {
            OperationsTheme {
                RootNavigation(state.value, onLogout = {
                    state.value = SessionState.SignedOut
                }, onRetry = {})
            }
        }
        composeRule.onNodeWithText("Session verified").assertIsDisplayed()
        composeRule.onNodeWithText("Sign out").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Sign in required").assertIsDisplayed()
        composeRule.onNodeWithText("Session verified").assertDoesNotExist()
    }
}
