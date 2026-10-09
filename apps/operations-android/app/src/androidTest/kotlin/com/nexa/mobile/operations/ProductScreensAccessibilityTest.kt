package com.nexa.mobile.operations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsContext
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedSkuProjection
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ProductCandidate
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters.CatalogOperationsContextAdapter
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.R as CatalogR
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ConfirmedSkuScreen
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchScreen
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchStatus
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchUiState
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.OperationsWorkEntryScreen
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.TaskVisibilityHint
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseUiState
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WorkEntryStatus
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductScreensAccessibilityTest {
    @get:Rule val composeRule = createComposeRule()

    private fun localizedString(resourceId: Int, vararg formatArgs: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(
            resourceId,
            *formatArgs
        )

    @Test
    fun identityScreenKeepsFieldsVisibilityControlAndSubmitReachableAtLargeFont() {
        val passwordVisible = mutableStateOf(false)
        var signInCalls = 0
        composeRule.setContent {
            OperationsTheme {
                CompactViewport {
                    AccessScreen(
                        state = AccessUiState(passwordVisible = passwordVisible.value),
                        onIdentifierChanged = {},
                        onPasswordChanged = {},
                        onPasswordVisibilityChanged = {
                            passwordVisible.value = !passwordVisible.value
                        },
                        onSignIn = { signInCalls += 1 }
                    )
                }
            }
        }

        val signInTitle = localizedString(AccessR.string.access_sign_in_title)
        val signInSubmit = localizedString(AccessR.string.access_submit)
        composeRule.onNode(hasText(signInTitle).and(!hasClickAction())).assertIsDisplayed()
        composeRule.onNodeWithText(localizedString(AccessR.string.access_sign_in_support))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedString(AccessR.string.access_identifier_label))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedString(AccessR.string.access_password_label))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            localizedString(AccessR.string.access_password_show)
        )
            .performScrollTo()
            .assertIsDisplayed()
            .assert(hasClickAction())
            .performClick()
        composeRule.onNodeWithContentDescription(
            localizedString(AccessR.string.access_password_hide)
        ).assertIsDisplayed()
        composeRule.onNode(hasText(signInSubmit).and(hasClickAction()))
            .performScrollTo()
            .assertIsDisplayed()
            .assert(hasClickAction())
            .performClick()
        composeRule.runOnIdle { assertEquals(1, signInCalls) }
    }

    @Test
    fun contextChooserCanReachAndSelectBelowFoldContextAtLargeFont() {
        val choices = listOf(
            WorkforceContextSummary(
                key = "context-1",
                companyName = "Nexa Demo Distribución",
                workspaceName = "Almacén Principal",
                permissionHint = PermissionHint.Available,
                isCurrent = true
            ),
            WorkforceContextSummary("context-2", "Nexa Demo Norte", "Almacén Secundario"),
            WorkforceContextSummary("context-3", "Nexa Demo Centro", "Almacén Central"),
            WorkforceContextSummary("context-4", "Nexa Demo Costa", "Almacén Costero"),
            WorkforceContextSummary("context-5", "Nexa Demo Sierra", "Almacén Andino"),
            WorkforceContextSummary("context-6", "Nexa Demo Sur", "Almacén Austral")
        )
        var selectedContext: String? = null
        composeRule.setContent {
            OperationsTheme {
                CompactViewport {
                    ContextChooserScreen(
                        state = ContextChooserUiState(
                            mode = ContextChooserMode.Initial,
                            phase = ContextChooserPhase.Choices,
                            choices = choices
                        ),
                        onSelect = { selectedContext = it },
                        onRetry = {},
                        onBack = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText(localizedString(AccessR.string.context_initial_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedString(AccessR.string.context_initial_support))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            localizedString(
                CoreR.string.nexa_context_choice_description,
                choices.last().companyName,
                choices.last().workspaceName
            )
        )
            .performScrollTo()
            .assertIsDisplayed()
            .assert(hasClickAction())
            .performClick()
        composeRule.runOnIdle { assertEquals("context-6", selectedContext) }
    }

    @Test
    fun permittedTaskAndContextActionRemainReachableAtLargeFont() {
        val activeContext = ActiveOperationsContext(
            companyName = "Nexa Demo Distribución",
            workspaceName = "Almacén Principal",
            authorityEpoch = 0
        )
        val state = WarehouseUiState(
            workEntryStatus = WorkEntryStatus.TaskAvailable,
            permissionHint = TaskVisibilityHint.Available,
            activeContext = activeContext,
            authorityEpoch = 0
        )
        var contextChanges = 0
        var taskOpens = 0
        composeRule.setContent {
            OperationsTheme {
                CompactViewport {
                    OperationsWorkEntryScreen(
                        state = state,
                        onChangeContext = { contextChanges += 1 },
                        onIdentifyProduct = { taskOpens += 1 }
                    )
                }
            }
        }

        val contextAction = composeRule.onNodeWithContentDescription(
            localizedString(
                CoreR.string.nexa_current_context_description,
                activeContext.companyName,
                activeContext.workspaceName
            )
        )
        contextAction.performScrollTo().assertIsDisplayed().assert(hasClickAction()).performClick()
        composeRule.onNodeWithText(localizedString(CatalogR.string.warehouse_work_available))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            localizedString(
                CoreR.string.nexa_task_row_action_description,
                localizedString(CatalogR.string.warehouse_identify_product),
                localizedString(CatalogR.string.warehouse_identify_product_support)
            )
        ).performScrollTo().assertIsDisplayed().assert(hasClickAction()).performClick()
        composeRule.runOnIdle {
            assertEquals(1, contextChanges)
            assertEquals(1, taskOpens)
        }
    }

    @Test
    fun productSearchKeepsQuerySubmitContextAndCandidateReachableAtLargeFont() {
        val activeContext = ActiveOperationsContext(
            companyName = "Nexa Demo Distribución",
            workspaceName = "Almacén Principal",
            authorityEpoch = 0
        )
        val candidate = ProductCandidate(
            key = "candidate-key",
            productDisplayName = "Queso Gouda Demo",
            brandOrVariant = "Lácteo",
            presentation = "Bloque · 500 g",
            sku = "SKU-DEMO-001"
        )
        val searchState = mutableStateOf(
            ProductSearchUiState(
                query = "queso",
                status = ProductSearchStatus.OneCandidate,
                candidates = listOf(candidate),
                authorityEpoch = 0
            )
        )
        var contextChanges = 0
        var searches = 0
        composeRule.setContent {
            OperationsTheme {
                CompactViewport {
                    ProductSearchScreen(
                        state = searchState.value,
                        activeContext = CatalogOperationsContextAdapter.from(activeContext),
                        onChangeContext = { contextChanges += 1 },
                        onBack = {},
                        onQueryChanged = { query ->
                            searchState.value = searchState.value.copy(query = query)
                        },
                        onSearch = { searches += 1 },
                        onLoadMore = {},
                        onSelectCandidate = {}
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription(
            localizedString(
                CoreR.string.nexa_current_context_description,
                activeContext.companyName,
                activeContext.workspaceName
            )
        ).performScrollTo().assertIsDisplayed().assert(hasClickAction()).performClick()
        val searchTitle = localizedString(CatalogR.string.warehouse_search_title)
        val searchFieldLabel = localizedString(CoreR.string.nexa_search_label)
        if (searchTitle == searchFieldLabel) {
            val searchLabels = composeRule.onAllNodesWithText(searchTitle)
            searchLabels.assertCountEquals(2)
            searchLabels[0].performScrollTo().assertIsDisplayed()
            searchLabels[1].performScrollTo().assertIsDisplayed()
        } else {
            val titleNodes = composeRule.onAllNodesWithText(searchTitle)
            titleNodes.assertCountEquals(1)
            titleNodes[0].performScrollTo().assertIsDisplayed()
            val fieldLabelNodes = composeRule.onAllNodesWithText(searchFieldLabel)
            fieldLabelNodes.assertCountEquals(1)
            fieldLabelNodes[0].performScrollTo().assertIsDisplayed()
        }
        composeRule.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assert(hasText("queso"))
        composeRule.onNodeWithText(
            localizedString(CoreR.string.nexa_search_button),
            useUnmergedTree = true
        )
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNode(hasClickAction().and(hasText("Queso Gouda Demo", substring = true)))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            localizedString(CoreR.string.nexa_candidate_sku, candidate.sku),
            useUnmergedTree = true
        )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, contextChanges)
            assertEquals(1, searches)
            assertEquals("queso", searchState.value.query)
        }
    }

    @Test
    fun confirmedSkuDetailsAndInventoryDisclaimerRemainReadableAtLargeFont() {
        val context = ActiveOperationsContext(
            companyName = "Nexa Demo Distribución",
            workspaceName = "Almacén Principal",
            authorityEpoch = 0
        )
        var contextChanges = 0
        composeRule.setContent {
            OperationsTheme {
                CompactViewport {
                    ConfirmedSkuScreen(
                        state = ConfirmedSkuProjection(
                            candidateKey = "candidate-key",
                            productDisplayName = "Queso Gouda Demo",
                            variant = "Lácteo",
                            presentation = "Bloque · 500 g",
                            sku = "SKU-DEMO-001",
                            brand = "Marca Demo",
                            unit = "unidad",
                            packaging = "Caja de 12",
                            coldChain = "Refrigerado",
                            context = CatalogOperationsContextAdapter.from(context),
                            authorityEpoch = 0
                        ),
                        onChangeContext = { contextChanges += 1 },
                        onBack = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText(
            localizedString(CatalogR.string.warehouse_confirmation_title)
        )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            localizedString(
                CoreR.string.nexa_current_context_description,
                context.companyName,
                context.workspaceName
            )
        ).performScrollTo().assertIsDisplayed().assert(hasClickAction()).performClick()
        composeRule.onNodeWithText("SKU-DEMO-001").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            localizedString(CatalogR.string.warehouse_inventory_disclaimer)
        )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Recibir", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Ajustar existencias", substring = true).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, contextChanges) }
    }

    @Test
    fun longCandidateKeepsItsIdentityReadableAndPendingSelectionUnavailableAtLargeFont() {
        val candidate = ProductCandidate(
            key = "candidate-long",
            productDisplayName =
                "Queso Gouda Demo de maduración prolongada para presentación institucional",
            brandOrVariant = "Lácteo de larga denominación",
            presentation = "Bloque refrigerado · 500 g",
            sku = "SKU-DEMO-LONG-001"
        )
        composeRule.setContent {
            OperationsTheme {
                CompactViewport {
                    ProductSearchScreen(
                        state = ProductSearchUiState(
                            query = "gouda",
                            status = ProductSearchStatus.ConfirmationPending,
                            candidates = listOf(candidate),
                            pendingCandidateKey = candidate.key
                        ),
                        activeContext = CatalogOperationsContext(
                            "Nexa Demo Distribución",
                            "Almacén Principal",
                            0
                        ),
                        onChangeContext = {},
                        onBack = {},
                        onQueryChanged = {},
                        onSearch = {},
                        onLoadMore = {},
                        onSelectCandidate = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText(candidate.productDisplayName, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            localizedString(CoreR.string.nexa_candidate_sku, candidate.sku),
            useUnmergedTree = true
        )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            candidateRowDescription(candidate)
        ).assertIsNotEnabled()
    }

    @Test
    fun longConfirmedProductAndInventoryDisclaimerRemainReachableAtLargeFont() {
        val productName =
            "Queso Gouda Demo de maduración prolongada para presentación institucional"
        composeRule.setContent {
            OperationsTheme {
                CompactViewport {
                    ConfirmedSkuScreen(
                        state = ConfirmedSkuProjection(
                            candidateKey = "candidate-long",
                            productDisplayName = productName,
                            variant = null,
                            presentation = "Bloque · 500 g",
                            sku = "SKU-DEMO-LONG-001",
                            brand = null,
                            unit = null,
                            packaging = null,
                            coldChain = "FROZEN",
                            context = CatalogOperationsContext(
                                "Nexa Demo Distribución",
                                "Almacén Principal",
                                0
                            ),
                            authorityEpoch = 0
                        ),
                        onChangeContext = {},
                        onBack = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText(productName).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("SKU-DEMO-LONG-001")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            localizedString(CatalogR.string.warehouse_value_cold_chain_frozen)
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            localizedString(CatalogR.string.warehouse_inventory_disclaimer)
        )
            .performScrollTo()
            .assertIsDisplayed()
    }
}

private fun localizedString(resourceId: Int, vararg formatArgs: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(resourceId, *formatArgs)

private fun candidateRowDescription(candidate: ProductCandidate): String {
    val candidateDetails = listOfNotNull(
        candidate.productDisplayName,
        candidate.brandOrVariant?.takeIf { it.isNotBlank() },
        candidate.presentation?.takeIf { it.isNotBlank() },
        localizedString(CoreR.string.nexa_candidate_sku, candidate.sku)
    ).joinToString()
    return localizedString(CoreR.string.nexa_candidate_row_action_description, candidateDetails)
}

@Composable
private fun CompactViewport(content: @Composable () -> Unit) {
    val currentDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(currentDensity.density, fontScale = 2f)
    ) {
        Box(
            modifier = Modifier
                .requiredSize(width = 320.dp, height = 640.dp)
                .clipToBounds()
        ) {
            content()
        }
    }
}
