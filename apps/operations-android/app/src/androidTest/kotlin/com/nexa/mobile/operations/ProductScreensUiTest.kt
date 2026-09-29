package com.nexa.mobile.operations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexa.mobile.operations.core.designsystem.OperationsTheme
import com.nexa.mobile.operations.feature.access.AccessScreen
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.ContextChooserMode
import com.nexa.mobile.operations.feature.access.ContextChooserPhase
import com.nexa.mobile.operations.feature.access.ContextChooserScreen
import com.nexa.mobile.operations.feature.access.ContextChooserUiState
import com.nexa.mobile.operations.feature.access.PermissionHint
import com.nexa.mobile.operations.feature.access.WorkforceContextSummary
import com.nexa.mobile.operations.feature.warehouse.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.ConfirmedSkuScreen
import com.nexa.mobile.operations.feature.warehouse.ConfirmedSkuUiState
import com.nexa.mobile.operations.feature.warehouse.OperationsWorkEntryScreen
import com.nexa.mobile.operations.feature.warehouse.ProductCandidate
import com.nexa.mobile.operations.feature.warehouse.ProductSearchScreen
import com.nexa.mobile.operations.feature.warehouse.ProductSearchStatus
import com.nexa.mobile.operations.feature.warehouse.ProductSearchUiState
import com.nexa.mobile.operations.feature.warehouse.TaskVisibilityHint
import com.nexa.mobile.operations.feature.warehouse.WarehouseUiState
import com.nexa.mobile.operations.feature.warehouse.WorkEntryStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductScreensUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun identityFormAndPasswordVisibilityHaveAccessibleLabels() {
        var passwordVisible by mutableStateOf(false)
        composeRule.setContent {
            OperationsTheme {
                AccessScreen(
                    state = AccessUiState(password = "secret", passwordVisible = passwordVisible),
                    onIdentifierChanged = {},
                    onPasswordChanged = {},
                    onPasswordVisibilityChanged = { passwordVisible = !passwordVisible },
                    onSignIn = {}
                )
            }
        }

        composeRule.onNodeWithText("NEXA").assertIsDisplayed()
        composeRule.onAllNodesWithText("Operations").assertCountEquals(1)
        composeRule.onNodeWithText("Inicia sesión").assertIsDisplayed()
        composeRule.onNodeWithText("Identificador").assertIsDisplayed()
        composeRule.onNodeWithText("Contraseña").assertIsDisplayed()
        composeRule.onNodeWithText("Iniciar sesión").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Mostrar contraseña").performClick()
        composeRule.onNodeWithContentDescription("Ocultar contraseña").assertIsDisplayed()
    }

    @Test
    fun identityFieldErrorsAreExposedOnEditableControls() {
        composeRule.setContent {
            OperationsTheme {
                AccessScreen(
                    state = AccessUiState(identifierError = true, passwordError = true),
                    onIdentifierChanged = {},
                    onPasswordChanged = {},
                    onPasswordVisibilityChanged = {},
                    onSignIn = {}
                )
            }
        }

        val fields = composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        fields.assertCountEquals(2)
        fields[0].assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.Error,
                "Ingresa tu identificador."
            )
        )
        fields[1].assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.Error,
                "Ingresa tu contraseña."
            )
        )
    }

    @Test
    fun contextChoiceHasMergedActionAndVisibleCurrentMarker() {
        var selectedContext: String? = null
        composeRule.setContent {
            OperationsTheme {
                ContextChooserScreen(
                    state = ContextChooserUiState(
                        mode = ContextChooserMode.Initial,
                        phase = ContextChooserPhase.Choices,
                        choices = listOf(currentContext, alternateContext)
                    ),
                    onSelect = { selectedContext = it },
                    onRetry = {},
                    onBack = {}
                )
            }
        }

        val currentRow = composeRule.onNodeWithContentDescription(
            "Contexto actual: Nexa Demo Distribución, Almacén Principal"
        )
        currentRow.assert(hasClickAction())
        currentRow.assertIsDisplayed()
        composeRule.onNodeWithText("Actual", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("context-primary").assertDoesNotExist()

        composeRule.onNodeWithContentDescription(
            "Nexa Demo Norte, Almacén Secundario"
        ).performClick()
        composeRule.runOnIdle { assertEquals("context-alternate", selectedContext) }
    }

    @Test
    fun operationsWorkEntryShowsOnlyTheAcceptedProductTask() {
        composeRule.setContent {
            OperationsTheme {
                OperationsWorkEntryScreen(
                    state = availableWorkState,
                    onChangeContext = {},
                    onIdentifyProduct = {}
                )
            }
        }

        composeRule.onAllNodesWithText("Operations").assertCountEquals(1)
        composeRule.onNodeWithText("Trabajo disponible").assertIsDisplayed()
        composeRule.onNodeWithText("Identificar producto").assertIsDisplayed()
        composeRule.onNodeWithText("Recibir", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Picking", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Dashboard", substring = true).assertDoesNotExist()
    }

    @Test
    fun manualSearchNeedsExplicitSubmitAndCandidateIsActionable() {
        var query by mutableStateOf("")
        var searchCalls by mutableIntStateOf(0)
        composeRule.setContent {
            OperationsTheme {
                ProductSearchScreen(
                    state = ProductSearchUiState(
                        query = query,
                        status = if (query.isBlank()) {
                            ProductSearchStatus.Initial
                        } else {
                            ProductSearchStatus.Typing
                        },
                        authorityEpoch = 4
                    ),
                    activeContext = warehouseContext,
                    onChangeContext = {},
                    onBack = {},
                    onQueryChanged = { query = it },
                    onSearch = { searchCalls++ },
                    onLoadMore = {},
                    onSelectCandidate = {}
                )
            }
        }

        composeRule.onAllNodesWithText("Buscar producto").assertCountEquals(2)
        composeRule.onNode(hasSetTextAction()).performTextInput("queso")
        composeRule.runOnIdle {
            assertEquals("queso", query)
            assertEquals(0, searchCalls)
        }
        composeRule.onNodeWithText("Buscar", useUnmergedTree = true).performClick()
        composeRule.runOnIdle { assertEquals(1, searchCalls) }
    }

    @Test
    fun emptySearchShowsAnExplicitEmptyState() {
        composeRule.setContent {
            OperationsTheme {
                ProductSearchScreen(
                    state = ProductSearchUiState(
                        query = "nada",
                        status = ProductSearchStatus.Empty,
                        authorityEpoch = 4
                    ),
                    activeContext = warehouseContext,
                    onChangeContext = {},
                    onBack = {},
                    onQueryChanged = {},
                    onSearch = {},
                    onLoadMore = {},
                    onSelectCandidate = {}
                )
            }
        }

        composeRule.onNodeWithText("No encontramos productos").assertIsDisplayed()
        composeRule.onNodeWithText("SKU: SKU-DEMO-001").assertDoesNotExist()
    }

    @Test
    fun confirmedSkuIsDisplayOnlyAndExplainsInventoryWasNotChanged() {
        composeRule.setContent {
            OperationsTheme {
                ConfirmedSkuScreen(
                    state = confirmedSku,
                    onChangeContext = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("Identificación confirmada").assertIsDisplayed()
        composeRule.onNodeWithText("SKU-DEMO-001").assertIsDisplayed()
        composeRule.onNodeWithText("No se realizó ninguna operación de inventario.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Recibir", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Ajustar existencias", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Continuar a inventario", substring = true).assertDoesNotExist()
    }

    @Test
    fun permissionUnavailableExplainsSessionRemainsActive() {
        composeRule.setContent {
            OperationsTheme {
                OperationsWorkEntryScreen(
                    state = availableWorkState.copy(
                        workEntryStatus = WorkEntryStatus.PermissionUnavailable,
                        permissionHint = TaskVisibilityHint.Unavailable
                    ),
                    onChangeContext = {},
                    onIdentifyProduct = {}
                )
            }
        }

        composeRule.onNodeWithText("No tienes permiso para esta tarea").assertIsDisplayed()
        composeRule.onNodeWithText("Tu sesión sigue activa.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Identificar producto").assertDoesNotExist()
    }

    @Test
    fun candidateRowMergesProductIdentityForAccessibility() {
        composeRule.setContent {
            OperationsTheme {
                ProductSearchScreen(
                    state = ProductSearchUiState(
                        query = "queso",
                        status = ProductSearchStatus.OneCandidate,
                        candidates = listOf(candidate),
                        authorityEpoch = 4
                    ),
                    activeContext = warehouseContext,
                    onChangeContext = {},
                    onBack = {},
                    onQueryChanged = {},
                    onSearch = {},
                    onLoadMore = {},
                    onSelectCandidate = {}
                )
            }
        }

        composeRule.onNode(hasClickAction().and(hasText("Queso Gouda Demo", substring = true)))
            .assertIsDisplayed()
        composeRule.onNodeWithText("SKU: SKU-DEMO-001", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    private companion object {
        val currentContext = WorkforceContextSummary(
            key = "context-primary",
            companyName = "Nexa Demo Distribución",
            workspaceName = "Almacén Principal",
            permissionHint = PermissionHint.Available,
            isCurrent = true
        )
        val alternateContext = WorkforceContextSummary(
            key = "context-alternate",
            companyName = "Nexa Demo Norte",
            workspaceName = "Almacén Secundario",
            permissionHint = PermissionHint.Available
        )
        val warehouseContext = ActiveOperationsContext(
            companyName = currentContext.companyName,
            workspaceName = currentContext.workspaceName,
            authorityEpoch = 4
        )
        val availableWorkState = WarehouseUiState(
            workEntryStatus = WorkEntryStatus.TaskAvailable,
            permissionHint = TaskVisibilityHint.Available,
            activeContext = warehouseContext,
            authorityEpoch = warehouseContext.authorityEpoch
        )
        val candidate = ProductCandidate(
            key = "candidate-key",
            productDisplayName = "Queso Gouda Demo",
            brandOrVariant = "Lácteo",
            presentation = "Bloque · 500 g",
            sku = "SKU-DEMO-001"
        )
        val confirmedSku = ConfirmedSkuUiState(
            candidateKey = candidate.key,
            productDisplayName = candidate.productDisplayName,
            variant = candidate.brandOrVariant,
            presentation = candidate.presentation,
            sku = candidate.sku,
            brand = "Marca Demo",
            unit = "unidad",
            packaging = "Caja de 12",
            coldChain = "Refrigerado",
            context = warehouseContext,
            authorityEpoch = warehouseContext.authorityEpoch
        )
    }
}
