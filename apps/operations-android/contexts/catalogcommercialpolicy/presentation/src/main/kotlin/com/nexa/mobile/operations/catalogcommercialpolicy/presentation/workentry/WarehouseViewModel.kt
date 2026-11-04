package com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.CandidateConfirmationResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.ProductFilterOptionsResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.ProductSearchResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterKind
import com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse.CatalogOperationsContextProjector
import com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse.WarehouseGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductFilterStatus
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchStatus
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchUiState
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.ActiveOperationsContext
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.VerifiedOperationsIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WarehouseViewModel(
    private val gateway: WarehouseGateway,
    private val catalogContextProjector: CatalogOperationsContextProjector,
    initialState: WarehouseUiState = WarehouseUiState()
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState)
    val state = mutableState.asStateFlow()

    private var requestGeneration = 0L
    private var automaticCatalogListingAuthority: Pair<Long, VerifiedOperationsIdentity?>? = null

    fun enterOperations(context: ActiveOperationsContext, permissionHint: TaskVisibilityHint) {
        val current = mutableState.value
        val authority = context.authorityKey()
        val sameAuthority = current.activeContext?.authorityKey() == authority &&
            current.authorityEpoch == context.authorityEpoch
        if (!sameAuthority) requestGeneration++
        if (automaticCatalogListingAuthority != authority) {
            automaticCatalogListingAuthority = null
        }
        mutableState.value = WarehouseUiState(
            route = WarehouseRoute.WorkEntry,
            workEntryStatus = when (permissionHint) {
                TaskVisibilityHint.Available -> WorkEntryStatus.TaskAvailable
                TaskVisibilityHint.Unavailable -> WorkEntryStatus.PermissionUnavailable
                TaskVisibilityHint.Unknown -> WorkEntryStatus.PermissionUnknown
            },
            permissionHint = permissionHint,
            activeContext = context,
            search = current.search?.takeIf {
                sameAuthority && it.authorityEpoch == context.authorityEpoch
            },
            authorityEpoch = context.authorityEpoch
        )
    }

    fun showNoPermittedTask() {
        requestGeneration++
        mutableState.update {
            if (it.activeContext == null) return@update it
            it.copy(
                route = WarehouseRoute.WorkEntry,
                workEntryStatus = WorkEntryStatus.NoPermittedTask,
                search = null,
                confirmedSku = null
            )
        }
    }

    fun openPicking() {
        val current = mutableState.value
        if (current.activeContext == null ||
            current.workEntryStatus != WorkEntryStatus.TaskAvailable
        ) {
            return
        }
        requestGeneration++
        mutableState.value = current.copy(
            route = WarehouseRoute.Picking,
            confirmedSku = null
        )
    }

    fun openStockCondition() {
        val current = mutableState.value
        if (current.activeContext == null ||
            current.workEntryStatus != WorkEntryStatus.TaskAvailable
        ) {
            return
        }
        requestGeneration++
        mutableState.value =
            current.copy(route = WarehouseRoute.StockCondition, confirmedSku = null)
    }

    fun openReceiving() {
        val current = mutableState.value
        if (current.activeContext == null ||
            current.workEntryStatus != WorkEntryStatus.TaskAvailable
        ) {
            return
        }
        requestGeneration++
        mutableState.value =
            current.copy(route = WarehouseRoute.Receiving, confirmedSku = null)
    }

    fun openProductSearch() {
        val current = mutableState.value
        if (current.workEntryStatus != WorkEntryStatus.TaskAvailable ||
            current.activeContext == null
        ) {
            return
        }
        if (current.route != WarehouseRoute.ProductSearch) requestGeneration++
        val previousSearch = current.search?.takeIf { it.authorityEpoch == current.authorityEpoch }
        val search = if (current.route != WarehouseRoute.ProductSearch && previousSearch != null &&
            previousSearch.status in setOf(
                ProductSearchStatus.Loading,
                ProductSearchStatus.LoadingMore,
                ProductSearchStatus.ConfirmationPending
            )
        ) {
            previousSearch.copy(
                status = when (previousSearch.candidates.size) {
                    0 -> ProductSearchStatus.Initial
                    1 -> ProductSearchStatus.OneCandidate
                    else -> ProductSearchStatus.MultipleCandidates
                },
                pendingCandidateKey = null
            )
        } else {
            previousSearch ?: ProductSearchUiState(authorityEpoch = current.authorityEpoch)
        }
        mutableState.value = current.copy(
            route = WarehouseRoute.ProductSearch,
            search = search,
            confirmedSku = null
        )
        val authority = current.activeContext.authorityKey()
        val needsInitialListing = search.query.isBlank() && search.brand == null &&
            search.category == null && search.candidates.isEmpty() &&
            search.status in setOf(ProductSearchStatus.Initial, ProductSearchStatus.Loading)
        if (automaticCatalogListingAuthority != authority || needsInitialListing) {
            automaticCatalogListingAuthority = authority
            submitSearch()
        }
    }

    fun openScanner() {
        val current = mutableState.value
        if (current.workEntryStatus != WorkEntryStatus.TaskAvailable ||
            current.activeContext == null
        ) {
            return
        }
        requestGeneration++
        mutableState.value = current.copy(
            route = WarehouseRoute.Scanner,
            confirmedSku = null
        )
    }

    fun queryChanged(value: String) {
        val current = mutableState.value
        if (current.route != WarehouseRoute.ProductSearch) return
        requestGeneration++
        val search = current.search ?: ProductSearchUiState(authorityEpoch = current.authorityEpoch)
        mutableState.value = current.copy(
            search = search.copy(
                query = value,
                status = if (value.isBlank() && search.brand == null && search.category == null) {
                    ProductSearchStatus.Initial
                } else {
                    ProductSearchStatus.Typing
                },
                candidates = emptyList(),
                nextPageKey = null,
                pendingCandidateKey = null,
                errorMessage = null
            )
        )
    }

    fun openFilterOptions(kind: WarehouseCatalogFilterKind) {
        val current = mutableState.value
        if (current.route != WarehouseRoute.ProductSearch) return
        requestGeneration++
        val search = current.search ?: ProductSearchUiState(authorityEpoch = current.authorityEpoch)
        mutableState.value = current.copy(
            search = search.copy(
                filterKind = kind,
                filterSearch = "",
                filterOptions = emptyList(),
                filterPage = 0,
                nextFilterPage = null,
                filterTotal = 0,
                filterStatus = ProductFilterStatus.Idle
            )
        )
        loadFilterOptions()
    }

    fun closeFilterOptions() {
        val current = mutableState.value
        val search = current.search ?: return
        if (current.route != WarehouseRoute.ProductSearch || search.filterKind == null) return
        requestGeneration++
        mutableState.value = current.copy(
            search = search.copy(
                filterKind = null,
                filterSearch = "",
                filterOptions = emptyList(),
                filterPage = 0,
                nextFilterPage = null,
                filterTotal = 0,
                filterStatus = ProductFilterStatus.Idle
            )
        )
    }

    fun filterSearchChanged(value: String) {
        val current = mutableState.value
        val search = current.search ?: return
        if (current.route != WarehouseRoute.ProductSearch || search.filterKind == null) return
        requestGeneration++
        mutableState.value = current.copy(
            search = search.copy(
                filterSearch = value,
                filterOptions = emptyList(),
                filterPage = 0,
                nextFilterPage = null,
                filterTotal = 0,
                filterStatus = ProductFilterStatus.Idle
            )
        )
    }

    fun loadFilterOptions() {
        val current = mutableState.value
        val search = current.search ?: return
        val kind = search.filterKind ?: return
        if (current.route != WarehouseRoute.ProductSearch) return
        if (search.filterStatus in setOf(
                ProductFilterStatus.Loading,
                ProductFilterStatus.LoadingMore
            )
        ) {
            return
        }
        val filterSearch = search.filterSearch.trim()
        if (filterSearch.length > 120) {
            mutableState.value = current.copy(
                search = search.copy(filterStatus = ProductFilterStatus.InvalidQuery)
            )
            return
        }
        runFilterOptions(kind, filterSearch, page = 0, append = false)
    }

    fun loadMoreFilterOptions() {
        val current = mutableState.value
        val search = current.search ?: return
        val kind = search.filterKind ?: return
        val page = search.nextFilterPage ?: return
        if (current.route != WarehouseRoute.ProductSearch ||
            search.filterStatus !in setOf(
                ProductFilterStatus.Ready,
                ProductFilterStatus.Empty,
                ProductFilterStatus.LoadMoreFailed
            )
        ) {
            return
        }
        runFilterOptions(kind, search.filterSearch.trim(), page, append = true)
    }

    fun retryFilterOptions() {
        val search = mutableState.value.search ?: return
        if (search.filterStatus == ProductFilterStatus.LoadMoreFailed) {
            loadMoreFilterOptions()
        } else {
            loadFilterOptions()
        }
    }

    fun selectFilter(kind: WarehouseCatalogFilterKind, choice: WarehouseCatalogFilterChoice?) {
        val current = mutableState.value
        val search = current.search ?: return
        if (current.route != WarehouseRoute.ProductSearch || search.filterKind != kind) return
        val selected = choice?.let { requested ->
            search.filterOptions.firstOrNull { it.id == requested.id }
        }
        if (choice != null && selected == null) return
        requestGeneration++
        val updated = when (kind) {
            WarehouseCatalogFilterKind.Brand -> search.copy(brand = selected)
            WarehouseCatalogFilterKind.Category -> search.copy(category = selected)
        }
        val hasCriteria = updated.query.isNotBlank() || updated.brand != null ||
            updated.category != null
        mutableState.value = current.copy(
            search = updated.copy(
                status = if (hasCriteria) {
                    ProductSearchStatus.Typing
                } else {
                    ProductSearchStatus.Initial
                },
                candidates = emptyList(),
                nextPageKey = null,
                pendingCandidateKey = null,
                errorMessage = null,
                filterKind = null,
                filterSearch = "",
                filterOptions = emptyList(),
                filterPage = 0,
                nextFilterPage = null,
                filterTotal = 0,
                filterStatus = ProductFilterStatus.Idle
            )
        )
    }

    fun submitSearch() {
        val current = mutableState.value
        val search = current.search ?: return
        if (current.route != WarehouseRoute.ProductSearch) return
        if (search.status in setOf(
                ProductSearchStatus.Loading,
                ProductSearchStatus.LoadingMore,
                ProductSearchStatus.ConfirmationPending
            )
        ) {
            return
        }
        val query = search.query.trim()
        if (query.length > 120) {
            mutableState.value = current.copy(
                search = search.copy(
                    status = ProductSearchStatus.InvalidQuery,
                    errorMessage = ProductSearchStatus.InvalidQuery
                )
            )
            return
        }
        runSearch(query, search.brand?.name, search.category?.name, null, append = false)
    }

    fun loadMore() {
        val current = mutableState.value
        val search = current.search ?: return
        if (current.route != WarehouseRoute.ProductSearch || search.nextPageKey == null) return
        if (search.status !in setOf(
                ProductSearchStatus.OneCandidate,
                ProductSearchStatus.MultipleCandidates,
                ProductSearchStatus.LoadMoreFailed
            )
        ) {
            return
        }
        runSearch(
            query = search.query.trim(),
            brandName = search.brand?.name,
            categoryName = search.category?.name,
            pageKey = search.nextPageKey,
            append = true
        )
    }

    fun retrySearch() {
        val current = mutableState.value
        if (current.route != WarehouseRoute.ProductSearch) return
        val search = current.search ?: return
        when {
            search.status == ProductSearchStatus.LoadMoreFailed -> loadMore()
            else -> submitSearch()
        }
    }

    fun selectCandidate(candidateKey: String) {
        val current = mutableState.value
        val search = current.search ?: return
        if (current.route != WarehouseRoute.ProductSearch) return
        if (search.status !in
            setOf(
                ProductSearchStatus.OneCandidate,
                ProductSearchStatus.MultipleCandidates,
                ProductSearchStatus.LoadMoreFailed
            )
        ) {
            return
        }
        val candidate = search.candidates.firstOrNull { it.key == candidateKey } ?: return
        val context = current.activeContext ?: return
        val catalogContext = catalogContextProjector.project(context)
        val generation = ++requestGeneration
        val epoch = current.authorityEpoch
        mutableState.value = current.copy(
            search = search.copy(
                status = ProductSearchStatus.ConfirmationPending,
                pendingCandidateKey = candidate.key,
                errorMessage = null
            )
        )
        viewModelScope.launch {
            val result = runCatching { gateway.confirm(candidate, epoch, catalogContext) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    CandidateConfirmationResult.ServiceUnavailable
                }
            if (!isCurrent(generation, epoch)) return@launch
            when (result) {
                is CandidateConfirmationResult.Confirmed -> {
                    val confirmed = result.sku
                    if (confirmed.candidateKey != candidate.key ||
                        confirmed.authorityEpoch != epoch
                    ) {
                        mutableState.value = mutableState.value.copy(
                            search = mutableState.value.search?.copy(
                                status = ProductSearchStatus.CandidateUnavailable,
                                pendingCandidateKey = null,
                                errorMessage = ProductSearchStatus.CandidateUnavailable
                            )
                        )
                    } else {
                        mutableState.value = mutableState.value.copy(
                            route = WarehouseRoute.ConfirmedSku,
                            search = mutableState.value.search?.let {
                                it.copy(
                                    status = if (it.candidates.size == 1) {
                                        ProductSearchStatus.OneCandidate
                                    } else {
                                        ProductSearchStatus.MultipleCandidates
                                    },
                                    pendingCandidateKey = null
                                )
                            },
                            confirmedSku = confirmed.copy(context = catalogContext)
                        )
                    }
                }

                CandidateConfirmationResult.CandidateUnavailable -> updateSearchFailure(
                    ProductSearchStatus.CandidateUnavailable,
                    generation
                )

                CandidateConfirmationResult.NetworkUnavailable -> updateSearchFailure(
                    ProductSearchStatus.NetworkUnavailable,
                    generation
                )

                CandidateConfirmationResult.ServiceUnavailable -> updateSearchFailure(
                    ProductSearchStatus.ServiceUnavailable,
                    generation
                )

                CandidateConfirmationResult.PermissionDenied -> permissionDenied()

                CandidateConfirmationResult.ContextInvalidated -> invalidateContext()

                CandidateConfirmationResult.SessionInvalidated -> invalidateSession()

                CandidateConfirmationResult.IntegrationUnavailable -> updateSearchFailure(
                    ProductSearchStatus.IntegrationUnavailable,
                    generation
                )

                CandidateConfirmationResult.UnknownOutcome -> updateSearchFailure(
                    ProductSearchStatus.CandidateUnavailable,
                    generation
                )
            }
        }
    }

    fun back() {
        val current = mutableState.value
        when (current.route) {
            WarehouseRoute.ConfirmedSku -> {
                val confirmed = current.confirmedSku
                val search = current.search
                if (
                    current.activeContext != null && confirmed != null && search != null &&
                    confirmed.authorityEpoch == current.authorityEpoch &&
                    search.authorityEpoch == current.authorityEpoch
                ) {
                    mutableState.value =
                        current.copy(route = WarehouseRoute.ProductSearch, confirmedSku = null)
                } else {
                    clearToWorkEntry()
                }
            }

            WarehouseRoute.ProductSearch -> {
                requestGeneration++
                mutableState.value = current.copy(
                    route = WarehouseRoute.WorkEntry,
                    search = null,
                    confirmedSku = null
                )
            }

            WarehouseRoute.Receiving, WarehouseRoute.Scanner, WarehouseRoute.StockCondition,
            WarehouseRoute.Picking -> {
                requestGeneration++
                mutableState.value = current.copy(
                    route = WarehouseRoute.WorkEntry,
                    search = null,
                    confirmedSku = null
                )
            }

            WarehouseRoute.WorkEntry -> Unit
        }
    }

    fun authorityReplaced(context: ActiveOperationsContext, permissionHint: TaskVisibilityHint) {
        enterOperations(context, permissionHint)
    }

    fun permissionHintChanged(permissionHint: TaskVisibilityHint) {
        val current = mutableState.value
        if (permissionHint == TaskVisibilityHint.Available &&
            current.permissionHint == TaskVisibilityHint.Available
        ) {
            return
        }
        requestGeneration++
        mutableState.value = current.copy(
            route = WarehouseRoute.WorkEntry,
            workEntryStatus = when (permissionHint) {
                TaskVisibilityHint.Available -> WorkEntryStatus.TaskAvailable
                TaskVisibilityHint.Unavailable -> WorkEntryStatus.PermissionUnavailable
                TaskVisibilityHint.Unknown -> WorkEntryStatus.PermissionUnknown
            },
            permissionHint = permissionHint,
            search = null,
            confirmedSku = null
        )
    }

    fun contextInvalidated() {
        requestGeneration++
        val previous = mutableState.value
        val nextEpoch = previous.authorityEpoch + 1
        mutableState.value = WarehouseUiState(
            route = WarehouseRoute.WorkEntry,
            workEntryStatus = WorkEntryStatus.ContextInvalidated,
            authorityEpoch = nextEpoch,
            invalidatedFromAuthorityEpoch = previous.activeContext?.authorityEpoch
        )
    }

    fun sessionInvalidated() {
        requestGeneration++
        val previous = mutableState.value
        val nextEpoch = previous.authorityEpoch + 1
        mutableState.value = WarehouseUiState(
            route = WarehouseRoute.WorkEntry,
            workEntryStatus = WorkEntryStatus.SessionInvalidated,
            authorityEpoch = nextEpoch,
            invalidatedFromAuthorityEpoch = previous.activeContext?.authorityEpoch
        )
    }

    private fun runSearch(
        query: String,
        brandName: String?,
        categoryName: String?,
        pageKey: String?,
        append: Boolean
    ) {
        val current = mutableState.value
        val previousSearch = current.search ?: return
        val context = current.activeContext ?: return
        val epoch = current.authorityEpoch
        val generation = ++requestGeneration
        mutableState.value = current.copy(
            search = previousSearch.copy(
                status = if (append) {
                    ProductSearchStatus.LoadingMore
                } else {
                    ProductSearchStatus.Loading
                },
                errorMessage = null
            )
        )
        viewModelScope.launch {
            if (!isCurrent(generation, epoch) || context.authorityEpoch != epoch) return@launch
            val result = runCatching {
                gateway.search(query, brandName, categoryName, pageKey, epoch)
            }
                .getOrElse {
                    if (it is CancellationException) throw it
                    ProductSearchResult.ServiceUnavailable
                }
            if (!isCurrent(generation, epoch) || context.authorityEpoch != epoch) return@launch
            when (result) {
                is ProductSearchResult.Page -> {
                    val currentSearch = mutableState.value.search ?: return@launch
                    val items = (
                        if (append) {
                            currentSearch.candidates + result.items
                        } else {
                            result.items
                        }
                        ).distinctBy { it.key }
                    val status = when {
                        items.isEmpty() -> ProductSearchStatus.Empty
                        items.size == 1 -> ProductSearchStatus.OneCandidate
                        else -> ProductSearchStatus.MultipleCandidates
                    }
                    mutableState.value = mutableState.value.copy(
                        search = currentSearch.copy(
                            query = query,
                            brand = currentSearch.brand,
                            category = currentSearch.category,
                            status = status,
                            candidates = items,
                            nextPageKey = result.nextPageKey,
                            pendingCandidateKey = null,
                            errorMessage = null
                        )
                    )
                }

                ProductSearchResult.NetworkUnavailable -> searchFailure(
                    if (append) {
                        ProductSearchStatus.LoadMoreFailed
                    } else {
                        ProductSearchStatus.NetworkUnavailable
                    },
                    generation
                )

                ProductSearchResult.ServiceUnavailable -> searchFailure(
                    if (append) {
                        ProductSearchStatus.LoadMoreFailed
                    } else {
                        ProductSearchStatus.ServiceUnavailable
                    },
                    generation
                )

                ProductSearchResult.InvalidQuery -> searchFailure(
                    ProductSearchStatus.InvalidQuery,
                    generation
                )

                ProductSearchResult.PermissionDenied -> permissionDenied()

                ProductSearchResult.ContextInvalidated -> invalidateContext()

                ProductSearchResult.SessionInvalidated -> invalidateSession()

                ProductSearchResult.IntegrationUnavailable -> searchFailure(
                    if (append) {
                        ProductSearchStatus.LoadMoreFailed
                    } else {
                        ProductSearchStatus.IntegrationUnavailable
                    },
                    generation
                )
            }
        }
    }

    private fun runFilterOptions(
        kind: WarehouseCatalogFilterKind,
        filterSearch: String,
        page: Int,
        append: Boolean
    ) {
        val current = mutableState.value
        val previous = current.search ?: return
        val context = current.activeContext ?: return
        val epoch = current.authorityEpoch
        val generation = ++requestGeneration
        mutableState.value = current.copy(
            search = previous.copy(
                filterStatus = if (append) {
                    ProductFilterStatus.LoadingMore
                } else {
                    ProductFilterStatus.Loading
                }
            )
        )
        viewModelScope.launch {
            val result = runCatching {
                gateway.filterOptions(kind, filterSearch, page, epoch)
            }.getOrElse {
                if (it is CancellationException) throw it
                ProductFilterOptionsResult.ServiceUnavailable
            }
            if (!isCurrent(generation, epoch) ||
                mutableState.value.route != WarehouseRoute.ProductSearch
            ) {
                return@launch
            }
            when (result) {
                is ProductFilterOptionsResult.Page -> {
                    val value = result.value
                    if (value.page != page || value.size <= 0 || value.total < 0 ||
                        value.items.size > value.size || value.total < value.items.size
                    ) {
                        updateFilterFailure(ProductFilterStatus.ServiceUnavailable, generation)
                        return@launch
                    }
                    val currentSearch = mutableState.value.search ?: return@launch
                    val pageItems = if (append) {
                        currentSearch.filterOptions + value.items
                    } else {
                        value.items
                    }
                    val items = pageItems
                        .distinctBy { it.id }
                    val nextPage = (page + 1).takeIf {
                        it.toLong() * value.size < value.total
                    }
                    mutableState.value = mutableState.value.copy(
                        search = currentSearch.copy(
                            filterOptions = items,
                            filterPage = page,
                            nextFilterPage = nextPage,
                            filterTotal = value.total,
                            filterStatus = if (items.isEmpty()) {
                                ProductFilterStatus.Empty
                            } else {
                                ProductFilterStatus.Ready
                            }
                        )
                    )
                }

                ProductFilterOptionsResult.InvalidQuery -> updateFilterFailure(
                    ProductFilterStatus.InvalidQuery,
                    generation
                )

                ProductFilterOptionsResult.NetworkUnavailable -> updateFilterFailure(
                    if (append) {
                        ProductFilterStatus.LoadMoreFailed
                    } else {
                        ProductFilterStatus.NetworkUnavailable
                    },
                    generation
                )

                ProductFilterOptionsResult.ServiceUnavailable -> updateFilterFailure(
                    if (append) {
                        ProductFilterStatus.LoadMoreFailed
                    } else {
                        ProductFilterStatus.ServiceUnavailable
                    },
                    generation
                )

                ProductFilterOptionsResult.PermissionDenied -> permissionDenied()

                ProductFilterOptionsResult.ContextInvalidated -> invalidateContext()

                ProductFilterOptionsResult.SessionInvalidated -> invalidateSession()
            }
        }
    }

    private fun updateFilterFailure(status: ProductFilterStatus, generation: Long) {
        if (generation != requestGeneration) return
        mutableState.update { current ->
            val search = current.search ?: return@update current
            current.copy(search = search.copy(filterStatus = status))
        }
    }

    private fun searchFailure(status: ProductSearchStatus, generation: Long) {
        if (generation != requestGeneration) return
        mutableState.update { current ->
            val search = current.search ?: return@update current
            current.copy(
                search = search.copy(
                    status = status,
                    pendingCandidateKey = null,
                    errorMessage = status
                )
            )
        }
    }

    private fun permissionDenied() {
        permissionHintChanged(TaskVisibilityHint.Unavailable)
    }

    private fun updateSearchFailure(status: ProductSearchStatus, generation: Long) {
        if (generation != requestGeneration) return
        mutableState.update { current ->
            val search = current.search ?: return@update current
            current.copy(
                search = search.copy(
                    status = status,
                    pendingCandidateKey = null,
                    errorMessage = status
                )
            )
        }
    }

    private fun isCurrent(generation: Long, epoch: Long): Boolean =
        generation == requestGeneration && epoch == mutableState.value.authorityEpoch

    private fun clearToWorkEntry() {
        requestGeneration++
        mutableState.update {
            it.copy(route = WarehouseRoute.WorkEntry, confirmedSku = null)
        }
    }

    private fun ActiveOperationsContext.authorityKey(): Pair<Long, VerifiedOperationsIdentity?> =
        authorityEpoch to verifiedIdentity

    private fun invalidateContext() {
        contextInvalidated()
    }

    private fun invalidateSession() {
        sessionInvalidated()
    }
}
