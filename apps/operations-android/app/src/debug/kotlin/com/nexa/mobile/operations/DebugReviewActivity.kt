package com.nexa.mobile.operations

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackBanner
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackTone
import com.nexa.mobile.operations.core.designsystem.NexaPrimaryButton
import com.nexa.mobile.operations.core.designsystem.OperationsTheme
import com.nexa.mobile.operations.feature.access.AccessNotice
import com.nexa.mobile.operations.feature.access.AccessScreen
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.ContextChooserMode
import com.nexa.mobile.operations.feature.access.ContextChooserPhase
import com.nexa.mobile.operations.feature.access.ContextChooserScreen
import com.nexa.mobile.operations.feature.access.ContextChooserUiState
import com.nexa.mobile.operations.feature.access.ContextUnavailableReason
import com.nexa.mobile.operations.feature.access.PermissionHint
import com.nexa.mobile.operations.feature.access.R as AccessR
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
import com.nexa.mobile.operations.feature.warehouse.WarehouseRoute
import com.nexa.mobile.operations.feature.warehouse.WarehouseUiState
import com.nexa.mobile.operations.feature.warehouse.WorkEntryStatus

class DebugReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OperationsTheme { DebugReviewExperience() } }
    }
}

private enum class ReviewGroup(val title: Int) {
    Access(R.string.review_group_access),
    Context(R.string.review_group_context),
    Operations(R.string.review_group_operations),
    Search(R.string.review_group_search),
    Confirmed(R.string.review_group_confirmed)
}

private enum class ReviewScenario(val title: Int, val group: ReviewGroup) {
    Identity(R.string.review_identity, ReviewGroup.Access),
    Restoring(R.string.review_restoring, ReviewGroup.Access),
    Authenticating(R.string.review_authenticating, ReviewGroup.Access),
    InvalidInput(R.string.review_invalid_input, ReviewGroup.Access),
    Rejected(R.string.review_rejected, ReviewGroup.Access),
    NetworkUnavailable(R.string.review_network, ReviewGroup.Access),
    ServiceUnavailable(R.string.review_service, ReviewGroup.Access),
    SessionExpired(R.string.review_expired, ReviewGroup.Access),
    NoContexts(R.string.review_no_contexts, ReviewGroup.Access),
    InitialContexts(R.string.review_initial_contexts, ReviewGroup.Context),
    ChangeContexts(R.string.review_change_contexts, ReviewGroup.Context),
    ContextPending(R.string.review_context_pending, ReviewGroup.Context),
    ContextRejected(R.string.review_context_rejected, ReviewGroup.Context),
    ContextListUnavailable(R.string.review_context_unavailable, ReviewGroup.Context),
    ContextSwitchSuccess(R.string.review_context_success, ReviewGroup.Context),
    ContextSwitchRejected(R.string.review_context_rejection_old, ReviewGroup.Context),
    ContextSwitchUnknown(R.string.review_context_unknown, ReviewGroup.Context),
    WorkAvailable(R.string.review_work_available, ReviewGroup.Operations),
    NoTask(R.string.review_no_task, ReviewGroup.Operations),
    PermissionUnavailable(R.string.review_permission_unavailable, ReviewGroup.Operations),
    SearchInitial(R.string.review_search_initial, ReviewGroup.Search),
    SearchLoading(R.string.review_search_loading, ReviewGroup.Search),
    OneCandidate(R.string.review_one_candidate, ReviewGroup.Search),
    MultipleCandidates(R.string.review_multiple_candidates, ReviewGroup.Search),
    SearchEmpty(R.string.review_search_empty, ReviewGroup.Search),
    LoadingMore(R.string.review_loading_more, ReviewGroup.Search),
    LoadMoreFailed(R.string.review_load_more_failed, ReviewGroup.Search),
    ConfirmationPending(R.string.review_confirmation_pending, ReviewGroup.Search),
    CandidateUnavailable(R.string.review_candidate_unavailable, ReviewGroup.Search),
    PermissionDenied(R.string.review_permission_denied, ReviewGroup.Search),
    SearchNetworkUnavailable(R.string.review_search_network, ReviewGroup.Search),
    ConfirmedSku(R.string.review_confirmed_sku, ReviewGroup.Confirmed)
}

private sealed interface ReviewFrame {
    data class Access(val state: AccessUiState) : ReviewFrame
    data class ContextChooser(val state: ContextChooserUiState, val notice: AccessNotice? = null) :
        ReviewFrame
    data class Product(val state: WarehouseUiState, val notice: AccessNotice? = null) : ReviewFrame
}

@Composable
private fun DebugReviewExperience() {
    val context = LocalContext.current
    var scenario by remember { mutableStateOf(ReviewScenario.MultipleCandidates) }
    var selecting by remember { mutableStateOf(false) }
    val frame = remember(scenario) { ReviewScenarioProvider(context).frame(scenario) }
    var accessState by remember(scenario) {
        mutableStateOf((frame as? ReviewFrame.Access)?.state ?: AccessUiState())
    }
    var warehouseState by remember(scenario) {
        mutableStateOf((frame as? ReviewFrame.Product)?.state ?: WarehouseUiState())
    }

    Column(
        modifier = Modifier.fillMaxSize().background(NexaColors.Canvas).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(color = NexaColors.InfoSurface, shape = MaterialTheme.shapes.medium) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(R.string.review_banner_title),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(stringResource(scenario.title), style = MaterialTheme.typography.bodyMedium)
            }
        }
        NexaPrimaryButton(
            label = stringResource(
                if (selecting) R.string.review_close_scenarios else R.string.review_choose_scenario
            ),
            onClick = { selecting = !selecting }
        )
        if (selecting) {
            ScenarioPicker(onChoose = {
                scenario = it
                selecting = false
            })
        } else {
            when (val current = frame) {
                is ReviewFrame.Access -> AccessScreen(
                    state = accessState,
                    onIdentifierChanged = {
                        accessState =
                            accessState.copy(identifier = it, identifierError = false)
                    },
                    onPasswordChanged = {
                        accessState =
                            accessState.copy(password = it, passwordError = false)
                    },
                    onPasswordVisibilityChanged = {
                        accessState =
                            accessState.copy(passwordVisible = !accessState.passwordVisible)
                    },
                    onSignIn = { scenario = ReviewScenario.Authenticating },
                    onRetry = { scenario = ReviewScenario.ChangeContexts },
                    onClearLocalSession = { scenario = ReviewScenario.Identity },
                    showRetry = scenario == ReviewScenario.Restoring
                )

                is ReviewFrame.ContextChooser -> ContextChooserScreen(
                    state = current.state,
                    notice = current.notice,
                    onSelect = { scenario = ReviewScenario.ContextPending },
                    onRetry = { scenario = ReviewScenario.ChangeContexts },
                    onBack = { scenario = ReviewScenario.WorkAvailable },
                    onClearLocalSession = { scenario = ReviewScenario.Identity }
                )

                is ReviewFrame.Product -> when (warehouseState.route) {
                    WarehouseRoute.WorkEntry -> OperationsWorkEntryScreen(
                        state = warehouseState,
                        onChangeContext = { scenario = ReviewScenario.ChangeContexts },
                        onIdentifyProduct = { scenario = ReviewScenario.MultipleCandidates }
                    )

                    WarehouseRoute.ProductSearch -> warehouseState.search?.let { search ->
                        ReviewNotice(current.notice)
                        ProductSearchScreen(
                            state = search,
                            activeContext = warehouseState.activeContext,
                            onChangeContext = { scenario = ReviewScenario.ChangeContexts },
                            onBack = { scenario = ReviewScenario.WorkAvailable },
                            onQueryChanged = { query ->
                                warehouseState = warehouseState.copy(
                                    search = search.copy(
                                        query = query,
                                        status = ProductSearchStatus.Typing
                                    )
                                )
                            },
                            onSearch = { scenario = ReviewScenario.SearchLoading },
                            onLoadMore = { scenario = ReviewScenario.LoadingMore },
                            onSelectCandidate = { scenario = ReviewScenario.ConfirmationPending }
                        )
                    }

                    WarehouseRoute.ConfirmedSku -> warehouseState.confirmedSku?.let { confirmed ->
                        ReviewNotice(current.notice)
                        ConfirmedSkuScreen(
                            state = confirmed,
                            onChangeContext = { scenario = ReviewScenario.ChangeContexts },
                            onBack = { scenario = ReviewScenario.MultipleCandidates }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScenarioPicker(onChoose: (ReviewScenario) -> Unit) {
    LazyColumn(
        modifier = Modifier.testTag("review-scenario-picker"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ReviewGroup.entries.forEach { group ->
            item(key = "group-${group.name}") {
                Text(
                    stringResource(group.title),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(
                ReviewScenario.entries.filter {
                    it.group == group
                },
                key = ReviewScenario::name
            ) { item ->
                NexaPrimaryButton(label = stringResource(item.title), onClick = { onChoose(item) })
            }
        }
    }
}

@Composable
private fun ReviewNotice(notice: AccessNotice?) {
    val resource = when (notice) {
        AccessNotice.ContextSelectionRejected -> AccessR.string.access_notice_context_rejected
        AccessNotice.ContextSelectionUnavailable -> AccessR.string.access_notice_context_unavailable
        AccessNotice.UnknownContextOutcome -> AccessR.string.access_notice_context_unknown
        else -> null
    }
    if (resource != null) {
        NexaFeedbackBanner(stringResource(resource), NexaFeedbackTone.Warning)
    }
}

private class ReviewScenarioProvider(private val context: Context) {
    private val primaryContext = WorkforceContextSummary(
        key = "debug-context-primary",
        companyName = context.getString(R.string.review_demo_company),
        workspaceName = context.getString(R.string.review_demo_workspace),
        permissionHint = PermissionHint.Available,
        isCurrent = true
    )
    private val alternateContext = WorkforceContextSummary(
        key = "debug-context-alternate",
        companyName = context.getString(R.string.review_demo_alternate_company),
        workspaceName = context.getString(R.string.review_demo_alternate_workspace),
        permissionHint = PermissionHint.Available
    )
    private val activePrimary = primaryContext.operationsContext(epoch = 1)
    private val activeAlternate = alternateContext.operationsContext(epoch = 2)
    private val gouda = ProductCandidate(
        key = "debug-candidate-gouda",
        productDisplayName = context.getString(R.string.review_demo_gouda),
        brandOrVariant = context.getString(R.string.review_demo_variant),
        presentation = context.getString(R.string.review_demo_presentation),
        sku = context.getString(R.string.review_demo_sku)
    )
    private val cheddar = ProductCandidate(
        key = "debug-candidate-cheddar",
        productDisplayName = context.getString(R.string.review_demo_cheddar),
        brandOrVariant = context.getString(R.string.review_demo_brand),
        presentation = context.getString(R.string.review_demo_presentation_alt),
        sku = context.getString(R.string.review_demo_sku_alt)
    )

    fun frame(scenario: ReviewScenario): ReviewFrame = when (scenario) {
        ReviewScenario.Identity -> ReviewFrame.Access(AccessUiState())

        ReviewScenario.Restoring -> ReviewFrame.Access(
            AccessUiState(stage = AccessStage.RestoringSession)
        )

        ReviewScenario.Authenticating -> ReviewFrame.Access(
            AccessUiState(stage = AccessStage.Authenticating)
        )

        ReviewScenario.InvalidInput -> ReviewFrame.Access(
            AccessUiState(identifierError = true, passwordError = true)
        )

        ReviewScenario.Rejected -> ReviewFrame.Access(
            AccessUiState(notice = AccessNotice.AuthenticationRejected)
        )

        ReviewScenario.NetworkUnavailable -> ReviewFrame.Access(
            AccessUiState(notice = AccessNotice.NetworkUnavailable)
        )

        ReviewScenario.ServiceUnavailable -> ReviewFrame.Access(
            AccessUiState(notice = AccessNotice.ServiceUnavailable)
        )

        ReviewScenario.SessionExpired -> ReviewFrame.Access(
            AccessUiState(stage = AccessStage.SessionExpired, notice = AccessNotice.SessionExpired)
        )

        ReviewScenario.NoContexts -> ReviewFrame.Access(
            AccessUiState(stage = AccessStage.NoContexts, notice = AccessNotice.NoContexts)
        )

        ReviewScenario.InitialContexts -> ReviewFrame.ContextChooser(
            ContextChooserUiState(
                mode = ContextChooserMode.Initial,
                phase = ContextChooserPhase.Choices,
                choices = listOf(primaryContext.copy(isCurrent = false), alternateContext)
            )
        )

        ReviewScenario.ChangeContexts -> ReviewFrame.ContextChooser(
            ContextChooserUiState(
                mode = ContextChooserMode.Change,
                phase = ContextChooserPhase.Choices,
                current = primaryContext,
                choices = listOf(primaryContext, alternateContext)
            )
        )

        ReviewScenario.ContextPending -> ReviewFrame.ContextChooser(
            ContextChooserUiState(
                mode = ContextChooserMode.Change,
                phase = ContextChooserPhase.SelectionPending,
                current = primaryContext,
                choices = listOf(primaryContext, alternateContext),
                pendingKey = alternateContext.key
            )
        )

        ReviewScenario.ContextRejected -> ReviewFrame.ContextChooser(
            ContextChooserUiState(
                mode = ContextChooserMode.Initial,
                phase = ContextChooserPhase.Choices,
                choices = listOf(primaryContext.copy(isCurrent = false), alternateContext)
            ),
            AccessNotice.ContextSelectionRejected
        )

        ReviewScenario.ContextListUnavailable -> ReviewFrame.ContextChooser(
            ContextChooserUiState(
                mode = ContextChooserMode.Change,
                phase = ContextChooserPhase.ListUnavailable,
                current = primaryContext,
                unavailableReason = ContextUnavailableReason.CurrentContextRemainsValid
            ),
            AccessNotice.IntegrationUnavailable
        )

        ReviewScenario.ContextSwitchSuccess -> productWork(activeAlternate)

        ReviewScenario.ContextSwitchRejected -> productWork(
            activePrimary,
            AccessNotice.ContextSelectionRejected
        )

        ReviewScenario.ContextSwitchUnknown -> ReviewFrame.Access(
            AccessUiState(
                stage = AccessStage.IdentityRequired,
                notice = AccessNotice.UnknownContextOutcome,
                authorityEpoch = 2
            )
        )

        ReviewScenario.WorkAvailable -> productWork(activePrimary)

        ReviewScenario.NoTask -> productWork(
            activePrimary
        ).copyWarehouse(WorkEntryStatus.NoPermittedTask)

        ReviewScenario.PermissionUnavailable -> productWork(
            activePrimary
        ).copyWarehouse(WorkEntryStatus.PermissionUnavailable)

        ReviewScenario.SearchInitial -> productSearch(ProductSearchStatus.Initial)

        ReviewScenario.SearchLoading -> productSearch(ProductSearchStatus.Loading, query = "gouda")

        ReviewScenario.OneCandidate -> productSearch(
            ProductSearchStatus.OneCandidate,
            listOf(gouda)
        )

        ReviewScenario.MultipleCandidates -> productSearch(
            ProductSearchStatus.MultipleCandidates,
            listOf(gouda, cheddar),
            query = context.getString(R.string.review_demo_query)
        )

        ReviewScenario.SearchEmpty -> productSearch(
            ProductSearchStatus.Empty,
            query = context.getString(R.string.review_demo_query)
        )

        ReviewScenario.LoadingMore -> productSearch(
            ProductSearchStatus.LoadingMore,
            listOf(gouda, cheddar),
            nextPageKey = "debug-page-2"
        )

        ReviewScenario.LoadMoreFailed -> productSearch(
            ProductSearchStatus.LoadMoreFailed,
            listOf(gouda, cheddar),
            nextPageKey = "debug-page-2",
            error = ProductSearchStatus.LoadMoreFailed
        )

        ReviewScenario.ConfirmationPending -> productSearch(
            ProductSearchStatus.ConfirmationPending,
            listOf(gouda, cheddar),
            pendingCandidateKey = gouda.key
        )

        ReviewScenario.CandidateUnavailable -> productSearch(
            ProductSearchStatus.CandidateUnavailable,
            listOf(gouda, cheddar),
            error = ProductSearchStatus.CandidateUnavailable
        )

        ReviewScenario.PermissionDenied -> productSearch(
            ProductSearchStatus.PermissionDenied,
            listOf(gouda),
            error = ProductSearchStatus.PermissionDenied
        )

        ReviewScenario.SearchNetworkUnavailable -> productSearch(
            ProductSearchStatus.NetworkUnavailable,
            query = context.getString(R.string.review_demo_query),
            error = ProductSearchStatus.NetworkUnavailable
        )

        ReviewScenario.ConfirmedSku -> ReviewFrame.Product(
            WarehouseUiState(
                route = WarehouseRoute.ConfirmedSku,
                workEntryStatus = WorkEntryStatus.TaskAvailable,
                permissionHint = TaskVisibilityHint.Available,
                activeContext = activePrimary,
                search = ProductSearchUiState(
                    query = context.getString(R.string.review_demo_query),
                    status = ProductSearchStatus.OneCandidate,
                    candidates = listOf(gouda),
                    authorityEpoch = 1
                ),
                confirmedSku = ConfirmedSkuUiState(
                    candidateKey = gouda.key,
                    productDisplayName = gouda.productDisplayName,
                    variant = context.getString(R.string.review_demo_variant),
                    presentation = gouda.presentation,
                    sku = gouda.sku,
                    brand = context.getString(R.string.review_demo_brand),
                    unit = context.getString(R.string.review_demo_unit),
                    packaging = context.getString(R.string.review_demo_packaging),
                    coldChain = context.getString(R.string.review_demo_cold_chain),
                    context = activePrimary,
                    authorityEpoch = 1
                ),
                authorityEpoch = 1
            )
        )
    }

    private fun productWork(context: ActiveOperationsContext, notice: AccessNotice? = null) =
        ReviewFrame.Product(
            WarehouseUiState(
                route = WarehouseRoute.WorkEntry,
                workEntryStatus = WorkEntryStatus.TaskAvailable,
                permissionHint = TaskVisibilityHint.Available,
                activeContext = context,
                authorityEpoch = context.authorityEpoch
            ),
            notice
        )

    private fun productSearch(
        status: ProductSearchStatus,
        candidates: List<ProductCandidate> = emptyList(),
        query: String = context.getString(R.string.review_demo_query),
        nextPageKey: String? = null,
        pendingCandidateKey: String? = null,
        error: ProductSearchStatus? = null
    ) = ReviewFrame.Product(
        WarehouseUiState(
            route = WarehouseRoute.ProductSearch,
            workEntryStatus = WorkEntryStatus.TaskAvailable,
            permissionHint = TaskVisibilityHint.Available,
            activeContext = activePrimary,
            search = ProductSearchUiState(
                query = query,
                status = status,
                candidates = candidates,
                nextPageKey = nextPageKey,
                pendingCandidateKey = pendingCandidateKey,
                errorMessage = error,
                authorityEpoch = activePrimary.authorityEpoch
            ),
            authorityEpoch = activePrimary.authorityEpoch
        )
    )

    private fun ReviewFrame.Product.copyWarehouse(status: WorkEntryStatus): ReviewFrame.Product =
        copy(
            state = state.copy(workEntryStatus = status)
        )

    private fun WorkforceContextSummary.operationsContext(epoch: Long) = ActiveOperationsContext(
        companyName = companyName,
        workspaceName = workspaceName,
        authorityEpoch = epoch
    )
}
