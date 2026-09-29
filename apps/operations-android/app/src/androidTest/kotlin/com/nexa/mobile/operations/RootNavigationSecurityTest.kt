package com.nexa.mobile.operations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.designsystem.OperationsTheme
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.PermissionHint
import com.nexa.mobile.operations.feature.access.WorkforceContextSummary
import com.nexa.mobile.operations.feature.warehouse.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.TaskVisibilityHint
import com.nexa.mobile.operations.feature.warehouse.WarehouseUiState
import com.nexa.mobile.operations.feature.warehouse.WorkEntryStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootNavigationSecurityTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun activeFoundationSessionWithoutConfirmedClientContextStaysAtAccess() {
        composeRule.setContent {
            OperationsTheme {
                RootNavigation(
                    state = SessionState.Active,
                    accessState = AccessUiState(stage = AccessStage.IdentityRequired),
                    warehouseState = WarehouseUiState()
                )
            }
        }

        composeRule.onNodeWithText("Comprobando tu sesión").assertIsDisplayed()
        composeRule.onNodeWithText("Identificar producto").assertDoesNotExist()
        composeRule.onNodeWithText("Nexa Demo Distribución").assertDoesNotExist()
    }

    @Test
    fun signedOutSessionCannotRenderPreviouslyConfirmedProductContext() {
        composeRule.setContent {
            OperationsTheme {
                RootNavigation(
                    state = SessionState.SignedOut,
                    accessState = AccessUiState(
                        stage = AccessStage.WorkAuthorized,
                        activeContext = primaryContext,
                        authorityEpoch = 4
                    ),
                    warehouseState = authorizedWorkState
                )
            }
        }

        composeRule.onNodeWithText("Inicia sesión").assertIsDisplayed()
        composeRule.onNodeWithText("Identificar producto").assertDoesNotExist()
        composeRule.onNodeWithText("Nexa Demo Distribución").assertDoesNotExist()
    }

    @Test
    fun activeSessionAndMatchingConfirmedContextRenderWorkEntry() {
        composeRule.setContent {
            OperationsTheme {
                RootNavigation(
                    state = SessionState.Active,
                    accessState = AccessUiState(
                        stage = AccessStage.WorkAuthorized,
                        activeContext = primaryContext,
                        authorityEpoch = 4
                    ),
                    warehouseState = authorizedWorkState
                )
            }
        }

        composeRule.onNodeWithText("Identificar producto").assertIsDisplayed()
        composeRule.onNodeWithText("Almacén Principal").assertIsDisplayed()
        composeRule.onNodeWithText("Inicia sesión").assertDoesNotExist()
    }

    private companion object {
        val primaryContext = WorkforceContextSummary(
            key = "test-context",
            companyName = "Nexa Demo Distribución",
            workspaceName = "Almacén Principal",
            permissionHint = PermissionHint.Available,
            isCurrent = true
        )
        val authorizedWorkState = WarehouseUiState(
            workEntryStatus = WorkEntryStatus.TaskAvailable,
            permissionHint = TaskVisibilityHint.Available,
            activeContext = ActiveOperationsContext(
                companyName = primaryContext.companyName,
                workspaceName = primaryContext.workspaceName,
                authorityEpoch = 4
            ),
            authorityEpoch = 4
        )
    }
}
