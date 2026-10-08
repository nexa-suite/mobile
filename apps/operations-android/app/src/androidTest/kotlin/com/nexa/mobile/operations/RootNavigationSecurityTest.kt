package com.nexa.mobile.operations

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.designsystem.OperationsTheme
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.PermissionHint
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.WorkforceContextSummary
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.operations.ActiveOperationsContext
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.operations.VerifiedOperationsIdentity
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessStage
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessUiState
import com.nexa.mobile.operations.workentry.TaskVisibilityHint
import com.nexa.mobile.operations.workentry.WarehouseUiState
import com.nexa.mobile.operations.workentry.WorkEntryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun workEntryShowsGrantedActionsAndLogoutClearsProtectedNavigation() {
        val permissions = setOf("dispatch.read")
        val authority = VerifiedContextAuthority(
            userId = "user-1",
            tenantId = "tenant-1",
            workspaceId = "workspace-1",
            membershipId = "membership-1",
            permissions = permissions
        )
        val context = WorkforceContextSummary(
            key = "context-1",
            companyName = "Demo Tenant",
            workspaceName = "Central Workspace",
            permissionHint = PermissionHint.Unavailable,
            isCurrent = true,
            verifiedAuthority = authority
        )
        val session = mutableStateOf<SessionState>(SessionState.Active)
        val accessState = mutableStateOf(
            AccessUiState(
                stage = AccessStage.WorkAuthorized,
                activeContext = context,
                authorityEpoch = 4
            )
        )
        val warehouseState = mutableStateOf(
            WarehouseUiState(
                workEntryStatus = WorkEntryStatus.TaskAvailable,
                permissionHint = TaskVisibilityHint.Available,
                activeContext = ActiveOperationsContext(
                    companyName = context.companyName,
                    workspaceName = context.workspaceName,
                    authorityEpoch = 4,
                    verifiedIdentity = VerifiedOperationsIdentity(
                        authority.userId,
                        authority.tenantId,
                        authority.workspaceId,
                        authority.membershipId,
                        authority.permissions
                    )
                ),
                authorityEpoch = 4
            )
        )
        val entries = listOf(
            ConnectedOperationEntry(
                "dispatch.loads",
                "Cargas y paradas",
                setOf("dispatch.read")
            ),
            ConnectedOperationEntry(
                "warehouse.transfer",
                "Traslado interno",
                setOf("warehouse:write")
            ),
            ConnectedOperationEntry(
                "commercial.visit",
                "Visita al cliente",
                setOf("client.read")
            ),
            ConnectedOperationEntry(
                "driver.coordination-limits",
                "Privacidad y coordinación: límites actuales",
                setOf("dispatch.read"),
                visibleInHub = false
            )
        )
        var logoutCalls = 0

        composeRule.setContent {
            OperationsTheme {
                RootNavigation(
                    state = session.value,
                    accessState = accessState.value,
                    warehouseState = warehouseState.value,
                    connectedOperationEntries = entries,
                    onLogout = {
                        logoutCalls += 1
                        session.value = SessionState.SignedOut
                        accessState.value = AccessUiState(authorityEpoch = 5)
                        warehouseState.value = WarehouseUiState(authorityEpoch = 5)
                    }
                )
            }
        }

        composeRule.onNodeWithText("Cargas y paradas")
            .performScrollTo()
            .assertIsDisplayed()
            .assert(hasClickAction())
        composeRule.onNodeWithText("Traslado interno").assertDoesNotExist()
        composeRule.onNodeWithText("Visita al cliente").assertDoesNotExist()
        composeRule.onNodeWithText("Privacidad y coordinación: límites actuales")
            .assertDoesNotExist()
        composeRule.onNodeWithText("Cerrar sesión")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithText("Inicia sesión").assertIsDisplayed()
        composeRule.onNodeWithText("Cargas y paradas").assertDoesNotExist()
        composeRule.onNodeWithText("Identificar producto").assertDoesNotExist()
        composeRule.onNodeWithText("Demo Tenant").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, logoutCalls)
            assertEquals(SessionState.SignedOut, session.value)
            assertEquals(RootDestination.SignedOut, session.value.rootDestination())
            assertNull(accessState.value.activeContext)
            assertNull(warehouseState.value.activeContext)
        }
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
