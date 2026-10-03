package com.nexa.mobile.operations

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.designsystem.OperationsTheme
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.PermissionHint
import com.nexa.mobile.operations.feature.access.WorkforceContextSummary
import com.nexa.mobile.operations.feature.warehouse.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.CandidateConfirmationResult
import com.nexa.mobile.operations.feature.warehouse.ConfirmedSkuUiState
import com.nexa.mobile.operations.feature.warehouse.ProductCandidate
import com.nexa.mobile.operations.feature.warehouse.ProductSearchResult
import com.nexa.mobile.operations.feature.warehouse.ProductSearchUiState
import com.nexa.mobile.operations.feature.warehouse.TaskVisibilityHint
import com.nexa.mobile.operations.feature.warehouse.WarehouseGateway
import com.nexa.mobile.operations.feature.warehouse.WarehouseRoute
import com.nexa.mobile.operations.feature.warehouse.WarehouseUiState
import com.nexa.mobile.operations.feature.warehouse.WarehouseViewModel
import com.nexa.mobile.operations.feature.warehouse.WorkEntryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootCapabilityInvalidationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun permissionLossClearsDeepRouteAndRegrantRequiresNewIdentification() {
        val context = ActiveOperationsContext("Test Company", "Test Workspace", 4)
        val authority = WorkforceContextSummary(
            key = "test-membership",
            companyName = context.companyName,
            workspaceName = context.workspaceName,
            permissionHint = PermissionHint.Available,
            isCurrent = true
        )
        val access = mutableStateOf(
            AccessUiState(
                stage = AccessStage.WorkAuthorized,
                activeContext = authority,
                authorityEpoch = 4
            )
        )
        val warehouse = WarehouseViewModel(
            gateway = NoRemoteCalls,
            initialState = WarehouseUiState(
                route = WarehouseRoute.ConfirmedSku,
                workEntryStatus = WorkEntryStatus.TaskAvailable,
                permissionHint = TaskVisibilityHint.Available,
                activeContext = context,
                authorityEpoch = 4,
                search = ProductSearchUiState(query = "old selection", authorityEpoch = 4),
                confirmedSku = ConfirmedSkuUiState(
                    candidateKey = "test-candidate",
                    productDisplayName = "Test Product",
                    variant = null,
                    presentation = "Test presentation",
                    sku = "TEST-SKU",
                    brand = null,
                    unit = null,
                    packaging = null,
                    coldChain = null,
                    context = context,
                    authorityEpoch = 4
                )
            )
        )
        composeRule.setContent {
            OperationsTheme {
                RootNavigation(
                    state = SessionState.Active,
                    accessState = access.value,
                    warehouseState = warehouse.state.collectAsState().value,
                    onWarehouseBack = warehouse::back,
                    onIdentifyProduct = warehouse::openProductSearch
                )
            }
        }
        composeRule.onNodeWithText("Identificación confirmada").assertIsDisplayed()
        composeRule.runOnIdle {
            access.value = access.value.copy(
                activeContext = authority.copy(permissionHint = PermissionHint.Unavailable)
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Identificación confirmada").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(WarehouseRoute.WorkEntry, warehouse.state.value.route)
            assertNull(warehouse.state.value.confirmedSku)
            assertNull(warehouse.state.value.search)
            assertEquals(4L, warehouse.state.value.authorityEpoch)
            access.value = access.value.copy(activeContext = authority)
        }
        composeRule.onNodeWithText("Identificar producto")
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(WarehouseRoute.ProductSearch, warehouse.state.value.route)
            assertEquals("", warehouse.state.value.search?.query)
            assertNull(warehouse.state.value.confirmedSku)
        }
    }

    private object NoRemoteCalls : WarehouseGateway {
        override suspend fun search(
            query: String,
            pageKey: String?,
            authorityEpoch: Long
        ): ProductSearchResult = error("Navigation must not dispatch a search")

        override suspend fun confirm(
            candidate: ProductCandidate,
            authorityEpoch: Long,
            context: ActiveOperationsContext
        ): CandidateConfirmationResult = error("Navigation must not confirm a candidate")
    }
}
