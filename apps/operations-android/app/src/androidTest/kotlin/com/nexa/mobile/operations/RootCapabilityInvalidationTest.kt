package com.nexa.mobile.operations

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.CandidateConfirmationResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.ProductFilterOptionsResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.ProductSearchResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterKind
import com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse.WarehouseGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsContext
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedSkuProjection
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ProductCandidate
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters.CatalogOperationsContextAdapter
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.R as CatalogR
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchUiState
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.TaskVisibilityHint
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseRoute
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseUiState
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseViewModel
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WorkEntryStatus
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.designsystem.OperationsTheme
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.ActiveOperationsContext
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.VerifiedOperationsIdentity
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.PermissionHint
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.WorkforceContextSummary
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessStage
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessUiState
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
        val verifiedIdentity = VerifiedOperationsIdentity(
            userId = "user-1",
            tenantId = "tenant-1",
            workspaceId = "workspace-1",
            membershipId = "membership-1",
            permissions = setOf("inventory.read"),
            roles = setOf("WAREHOUSE")
        )
        val context = ActiveOperationsContext(
            "Test Company",
            "Test Workspace",
            4,
            verifiedIdentity
        )
        val authority = WorkforceContextSummary(
            key = "test-membership",
            companyName = context.companyName,
            workspaceName = context.workspaceName,
            permissionHint = PermissionHint.Available,
            isCurrent = true,
            verifiedAuthority = VerifiedContextAuthority(
                userId = verifiedIdentity.userId,
                tenantId = verifiedIdentity.tenantId,
                workspaceId = verifiedIdentity.workspaceId,
                membershipId = verifiedIdentity.membershipId,
                permissions = verifiedIdentity.permissions,
                roles = verifiedIdentity.roles
            )
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
            catalogContextProjector = CatalogOperationsContextAdapter,
            initialState = WarehouseUiState(
                route = WarehouseRoute.ConfirmedSku,
                workEntryStatus = WorkEntryStatus.TaskAvailable,
                permissionHint = TaskVisibilityHint.Available,
                activeContext = context,
                authorityEpoch = 4,
                search = ProductSearchUiState(query = "old selection", authorityEpoch = 4),
                confirmedSku = ConfirmedSkuProjection(
                    candidateKey = "test-candidate",
                    productDisplayName = "Test Product",
                    variant = null,
                    presentation = "Test presentation",
                    sku = "TEST-SKU",
                    brand = null,
                    unit = null,
                    packaging = null,
                    coldChain = null,
                    context = CatalogOperationsContextAdapter.from(context),
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
        composeRule.onNodeWithText(
            targetString(CatalogR.string.warehouse_visual_product_detail_title)
        ).assertIsDisplayed()
        composeRule.runOnIdle {
            access.value = access.value.copy(
                activeContext = authority.copy(permissionHint = PermissionHint.Unavailable)
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            targetString(CatalogR.string.warehouse_visual_product_detail_title)
        ).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(WarehouseRoute.WorkEntry, warehouse.state.value.route)
            assertNull(warehouse.state.value.confirmedSku)
            assertNull(warehouse.state.value.search)
            assertEquals(4L, warehouse.state.value.authorityEpoch)
            access.value = access.value.copy(activeContext = authority)
        }
        composeRule.onNodeWithText(
            targetString(R.string.nav_catalog)
        )
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(WarehouseRoute.ProductSearch, warehouse.state.value.route)
            assertEquals("", warehouse.state.value.search?.query)
            assertNull(warehouse.state.value.confirmedSku)
        }
    }

    private object NoRemoteCalls : WarehouseGateway {
        override suspend fun search(
            query: String,
            brandName: String?,
            categoryName: String?,
            pageKey: String?,
            authorityEpoch: Long
        ): ProductSearchResult = error("Navigation must not dispatch a search")

        override suspend fun filterOptions(
            kind: WarehouseCatalogFilterKind,
            search: String,
            page: Int,
            authorityEpoch: Long
        ): ProductFilterOptionsResult = error("Navigation must not load filter options")

        override suspend fun confirm(
            candidate: ProductCandidate,
            authorityEpoch: Long,
            context: CatalogOperationsContext
        ): CandidateConfirmationResult = error("Navigation must not confirm a candidate")
    }

    private fun targetString(resourceId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resourceId)
}
