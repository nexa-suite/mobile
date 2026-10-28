package com.nexa.mobile.operations

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexa.mobile.operations.core.designsystem.OperationsTheme
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.R as AccessR
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessScreen
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessStage
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessUiState
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessImeFocusTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    @RequiresDedicatedInstrumentationEnvironment
    fun identifierFocusSurvivesImeOpenOnWideWindow() {
        val imeBottomPx = AtomicInteger()
        val windowWidthDp = AtomicInteger()
        val windowHeightDp = AtomicInteger()
        val identifierLabel = InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(AccessR.string.access_identifier_label)
        val continueLabel = InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(AccessR.string.access_welcome_continue)

        composeRule.setContent {
            val density = LocalDensity.current
            val imeBottom = WindowInsets.ime.getBottom(density)
            val windowInfo = LocalWindowInfo.current
            val windowWidth = with(density) {
                windowInfo.containerSize.width.toDp().value.toInt()
            }
            val windowHeight = with(density) {
                windowInfo.containerSize.height.toDp().value.toInt()
            }
            SideEffect {
                imeBottomPx.set(imeBottom)
                windowWidthDp.set(windowWidth)
                windowHeightDp.set(windowHeight)
            }
            OperationsTheme {
                AccessScreen(
                    state = AccessUiState(
                        stage = AccessStage.IdentityRequired,
                        identifier = "qa-focus"
                    ),
                    onIdentifierChanged = {},
                    onPasswordChanged = {},
                    onPasswordVisibilityChanged = {},
                    onSignIn = {}
                )
            }
        }

        composeRule.waitForIdle()
        assertTrue("test requires a wide tablet window", windowWidthDp.get() >= 840)
        val initialWindowHeightDp = windowHeightDp.get()

        composeRule.onNodeWithContentDescription(continueLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(identifierLabel).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { imeBottomPx.get() > 0 }
        assertTrue(
            "window topology size changed with IME",
            windowHeightDp.get() == initialWindowHeightDp
        )
        composeRule.onNodeWithContentDescription(identifierLabel).assertIsFocused()

        Thread.sleep(750)
        composeRule.waitForIdle()
        assertTrue("IME closed after field focus", imeBottomPx.get() > 0)
        composeRule.onNodeWithContentDescription(identifierLabel).assertIsFocused()
    }
}
