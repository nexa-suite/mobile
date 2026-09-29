package com.nexa.mobile.operations

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.designsystem.OperationsTheme
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.PermissionHint
import com.nexa.mobile.operations.feature.access.WorkforceContextSummary
import com.nexa.mobile.operations.feature.warehouse.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.ProductCandidate
import com.nexa.mobile.operations.feature.warehouse.ProductSearchStatus
import com.nexa.mobile.operations.feature.warehouse.ProductSearchUiState
import com.nexa.mobile.operations.feature.warehouse.WarehouseRoute
import com.nexa.mobile.operations.feature.warehouse.WarehouseUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootNavigationStateTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun signedOutIdentityInputPersistsAndSignInShowsLoadingWithoutChangingRootSession() {
        val session = SessionState.SignedOut
        val accessState = mutableStateOf(AccessUiState(authorityEpoch = 0))
        val warehouseState = WarehouseUiState(authorityEpoch = 0)
        var signInCalls = 0

        composeRule.setContent {
            OperationsTheme {
                RootNavigation(
                    state = session,
                    accessState = accessState.value,
                    warehouseState = warehouseState,
                    onIdentifierChanged = { value ->
                        accessState.value = accessState.value.copy(identifier = value)
                    },
                    onPasswordChanged = { value ->
                        accessState.value = accessState.value.copy(password = value)
                    },
                    onSignIn = {
                        signInCalls += 1
                        accessState.value = accessState.value.copy(
                            stage = AccessStage.Authenticating,
                            password = "",
                            passwordVisible = false
                        )
                    }
                )
            }
        }

        composeRule.onNodeWithText("Almacén Principal").assertDoesNotExist()
        val identityFields = composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        identityFields.assertCountEquals(2)
        identityFields[0].performTextInput("operator-001")
        identityFields[1].performTextInput("secret-value")

        composeRule.runOnIdle {
            assertEquals("operator-001", accessState.value.identifier)
            assertEquals("secret-value", accessState.value.password)
            assertEquals(0L, accessState.value.authorityEpoch)
            assertEquals(SessionState.SignedOut, session)
            assertEquals(RootDestination.SignedOut, session.rootDestination())
        }
        val editedFields = composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        editedFields.assertCountEquals(2)
        val editableText = editedFields.fetchSemanticsNodes().map { node ->
            node.config[SemanticsProperties.EditableText].text
        }
        assertEquals("operator-001", editableText[0])
        // Check the password field's displayed/semantics length without exposing its value.
        assertEquals(accessState.value.password.length, editableText[1].length)

        composeRule.onNodeWithText("Iniciar sesión")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithText("Iniciando sesión").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Estamos comprobando tu identidad.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Identificador").assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule.onNodeWithText("Iniciar sesión").assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(1, signInCalls)
            assertEquals(AccessStage.Authenticating, accessState.value.stage)
            assertEquals("", accessState.value.password)
            assertEquals(0L, accessState.value.authorityEpoch)
            assertEquals(SessionState.SignedOut, session)
            assertEquals(RootDestination.SignedOut, session.rootDestination())
        }
    }

    @Test
    fun searchQueryAndCandidateUpdateInSameProductSearchEntryWithoutConfirmation() {
        val session = SessionState.Active
        val workforceContext = WorkforceContextSummary(
            key = "context-primary",
            companyName = "Nexa Demo Distribución",
            workspaceName = "Almacén Principal",
            permissionHint = PermissionHint.Available,
            isCurrent = true
        )
        val activeContext = ActiveOperationsContext(
            companyName = workforceContext.companyName,
            workspaceName = workforceContext.workspaceName,
            authorityEpoch = 0
        )
        val accessState = AccessUiState(
            stage = AccessStage.WorkAuthorized,
            activeContext = workforceContext,
            authorityEpoch = 0
        )
        val candidate = ProductCandidate(
            key = "candidate-key",
            productDisplayName = "Queso Gouda Demo",
            brandOrVariant = "Lácteo",
            presentation = "Bloque · 500 g",
            sku = "SKU-DEMO-001"
        )
        val warehouseState = mutableStateOf(
            WarehouseUiState(
                route = WarehouseRoute.ProductSearch,
                activeContext = activeContext,
                search = ProductSearchUiState(
                    query = "",
                    status = ProductSearchStatus.Initial,
                    authorityEpoch = 0
                ),
                authorityEpoch = 0
            )
        )
        var searchCalls = 0
        var selectedCandidate: String? = null

        composeRule.setContent {
            OperationsTheme {
                RootNavigation(
                    state = session,
                    accessState = accessState,
                    warehouseState = warehouseState.value,
                    onQueryChanged = { query ->
                        val current = warehouseState.value
                        warehouseState.value = current.copy(
                            search = current.search?.copy(
                                query = query,
                                status = ProductSearchStatus.Typing
                            )
                        )
                    },
                    onSearch = {
                        searchCalls += 1
                        val current = warehouseState.value
                        warehouseState.value = current.copy(
                            search = current.search?.copy(
                                status = ProductSearchStatus.OneCandidate,
                                candidates = listOf(candidate)
                            )
                        )
                    },
                    onSelectCandidate = { selectedCandidate = it }
                )
            }
        }

        val queryField = composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        queryField.assertCountEquals(1)
        queryField[0].performTextInput("queso")
        composeRule.runOnIdle {
            assertEquals("queso", warehouseState.value.search?.query)
            assertEquals(ProductSearchStatus.Typing, warehouseState.value.search?.status)
            assertEquals(WarehouseRoute.ProductSearch, warehouseState.value.route)
            assertEquals(0L, warehouseState.value.authorityEpoch)
            assertEquals(0L, warehouseState.value.search?.authorityEpoch)
        }

        Espresso.closeSoftKeyboard()
        composeRule.onNode(hasClickAction() and hasText("Buscar"))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, searchCalls)
            assertEquals("queso", warehouseState.value.search?.query)
            assertEquals(ProductSearchStatus.OneCandidate, warehouseState.value.search?.status)
            assertEquals(listOf(candidate), warehouseState.value.search?.candidates)
            assertNull(warehouseState.value.confirmedSku)
            assertNull(selectedCandidate)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Queso Gouda Demo"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Queso Gouda Demo")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("SKU: SKU-DEMO-001", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Identificación confirmada").assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(1, searchCalls)
            assertEquals("queso", warehouseState.value.search?.query)
            assertEquals(ProductSearchStatus.OneCandidate, warehouseState.value.search?.status)
            assertEquals(1, warehouseState.value.search?.candidates?.size)
            assertEquals(WarehouseRoute.ProductSearch, warehouseState.value.route)
            assertEquals(0L, accessState.authorityEpoch)
            assertEquals(0L, warehouseState.value.authorityEpoch)
            assertEquals(0L, warehouseState.value.search?.authorityEpoch)
            assertNull(warehouseState.value.confirmedSku)
            assertNull(selectedCandidate)
            assertEquals(RootDestination.Active, session.rootDestination())
        }
    }
}
