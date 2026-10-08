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
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.test.platform.app.InstrumentationRegistry
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedSkuProjection
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ProductCandidate
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters.CatalogOperationsContextAdapter
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.R as CatalogR
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ConfirmedSkuScreen
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchScreen
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchStatus
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchUiState
import com.nexa.mobile.operations.core.designsystem.OperationsTheme
import com.nexa.mobile.operations.core.designsystem.R as CoreR
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.ActiveOperationsContext
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.PermissionHint
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.WorkforceContextSummary
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.R as AccessR
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessScreen
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessUiState
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.ContextChooserMode
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.ContextChooserPhase
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.ContextChooserScreen
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.ContextChooserUiState
import com.nexa.mobile.operations.workentry.OperationsWorkEntryScreen
import com.nexa.mobile.operations.workentry.TaskVisibilityHint
import com.nexa.mobile.operations.workentry.WarehouseUiState
import com.nexa.mobile.operations.workentry.WorkEntryStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductScreensUiTest {
    @get:Rule val composeRule = createComposeRule()

    private fun localizedString(resourceId: Int, vararg formatArgs: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(
            resourceId,
            *formatArgs
        )

    private fun assertProductSearchLabelsAppearExactlyOncePerOwner() {
        val searchTitle = localizedString(CatalogR.string.warehouse_search_title)
        val searchFieldLabel = localizedString(CoreR.string.nexa_search_label)
        if (searchTitle == searchFieldLabel) {
            composeRule.onAllNodesWithText(searchTitle).assertCountEquals(2)
        } else {
            composeRule.onAllNodesWithText(searchTitle).assertCountEquals(1)
            composeRule.onAllNodesWithText(searchFieldLabel).assertCountEquals(1)
        }
    }

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

        composeRule.onNodeWithContentDescription(
            localizedString(AccessR.string.access_brand_description)
        ).assertIsDisplayed()
        composeRule.onAllNodesWithText(
            localizedString(AccessR.string.access_product_label)
        ).assertCountEquals(1)
        val signInTitle = localizedString(AccessR.string.access_sign_in_title)
        val signInSubmit = localizedString(AccessR.string.access_submit)
        composeRule.onNode(hasText(signInTitle).and(!hasClickAction())).assertIsDisplayed()
        composeRule.onNodeWithText(localizedString(AccessR.string.access_identifier_label))
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedString(AccessR.string.access_password_label))
            .assertIsDisplayed()
        composeRule.onNode(hasText(signInSubmit).and(hasClickAction())).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            localizedString(AccessR.string.access_password_show)
        ).performClick()
        composeRule.onNodeWithContentDescription(
            localizedString(AccessR.string.access_password_hide)
        ).assertIsDisplayed()
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
                localizedString(AccessR.string.access_identifier_error)
            )
        )
        fields[1].assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.Error,
                localizedString(AccessR.string.access_password_error)
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
            localizedString(
                CoreR.string.nexa_current_context_description,
                currentContext.companyName,
                currentContext.workspaceName
            )
        )
        currentRow.assert(hasClickAction())
        currentRow.assertIsDisplayed()
        composeRule.onNodeWithText(
            localizedString(CoreR.string.nexa_actual),
            useUnmergedTree = true
        ).assertIsDisplayed()
        composeRule.onNodeWithText("context-primary").assertDoesNotExist()

        composeRule.onNodeWithContentDescription(
            localizedString(
                CoreR.string.nexa_context_choice_description,
                alternateContext.companyName,
                alternateContext.workspaceName
            )
        ).performClick()
        composeRule.runOnIdle { assertEquals("context-alternate", selectedContext) }
    }

    @Test
    fun pendingContextSelectionKeepsChoicesVisibleButUnavailable() {
        var selectionCalls = 0
        composeRule.setContent {
            OperationsTheme {
                ContextChooserScreen(
                    state = ContextChooserUiState(
                        mode = ContextChooserMode.Change,
                        phase = ContextChooserPhase.SelectionPending,
                        current = currentContext,
                        choices = listOf(currentContext, alternateContext),
                        pendingKey = alternateContext.key
                    ),
                    onSelect = { selectionCalls += 1 },
                    onRetry = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText(
            localizedString(CoreR.string.nexa_actual),
            useUnmergedTree = true
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            localizedString(
                CoreR.string.nexa_context_choice_description,
                alternateContext.companyName,
                alternateContext.workspaceName
            )
        )
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText(
            localizedString(AccessR.string.context_selection_pending)
        ).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, selectionCalls) }
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

        composeRule.onAllNodesWithText(
            localizedString(CatalogR.string.warehouse_operations_title)
        ).assertCountEquals(1)
        composeRule.onNodeWithText(localizedString(R.string.warehouse_work_available))
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedString(R.string.warehouse_identify_product))
            .assertIsDisplayed()
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
                    activeContext = CatalogOperationsContextAdapter.from(warehouseContext),
                    onChangeContext = {},
                    onBack = {},
                    onQueryChanged = { query = it },
                    onSearch = { searchCalls++ },
                    onLoadMore = {},
                    onSelectCandidate = {}
                )
            }
        }

        assertProductSearchLabelsAppearExactlyOncePerOwner()
        composeRule.onNode(hasSetTextAction()).performTextInput("queso")
        composeRule.runOnIdle {
            assertEquals("queso", query)
            assertEquals(0, searchCalls)
        }
        composeRule.onNodeWithText(
            localizedString(CoreR.string.nexa_search_button),
            useUnmergedTree = true
        ).performClick()
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
                    activeContext = CatalogOperationsContextAdapter.from(warehouseContext),
                    onChangeContext = {},
                    onBack = {},
                    onQueryChanged = {},
                    onSearch = {},
                    onLoadMore = {},
                    onSelectCandidate = {}
                )
            }
        }

        composeRule.onNodeWithText(
            localizedString(CatalogR.string.warehouse_search_empty_title)
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            localizedString(CoreR.string.nexa_candidate_sku, candidate.sku)
        ).assertDoesNotExist()
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

        composeRule.onNodeWithText(
            localizedString(CatalogR.string.warehouse_confirmation_title)
        ).assertIsDisplayed()
        composeRule.onNodeWithText("SKU-DEMO-001").assertIsDisplayed()
        composeRule.onNodeWithText(
            localizedString(CatalogR.string.warehouse_inventory_disclaimer)
        )
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

        composeRule.onNodeWithText(localizedString(R.string.warehouse_permission_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            localizedString(R.string.warehouse_permission_body),
            substring = true
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            localizedString(R.string.warehouse_identify_product)
        ).assertDoesNotExist()
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
                    activeContext = CatalogOperationsContextAdapter.from(warehouseContext),
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
        composeRule.onNodeWithText(
            localizedString(CoreR.string.nexa_candidate_sku, candidate.sku),
            useUnmergedTree = true
        )
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
            sku = "SKU-DEMO-001",
            imageFileName = "agriform-queso-grana-padano-dop-150g.png"
        )
        val confirmedSku = ConfirmedSkuProjection(
            candidateKey = candidate.key,
            productDisplayName = candidate.productDisplayName,
            variant = candidate.brandOrVariant,
            presentation = candidate.presentation,
            sku = candidate.sku,
            brand = "Marca Demo",
            unit = "unidad",
            packaging = "Caja de 12",
            coldChain = "Refrigerado",
            context = CatalogOperationsContextAdapter.from(warehouseContext),
            authorityEpoch = warehouseContext.authorityEpoch,
            imageFileName = "agriform-queso-grana-padano-dop-150g.png"
        )
    }
}
