package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WarehouseViewModel(
    private val gateway: WarehouseGateway,
    initialState: WarehouseUiState = WarehouseUiState()
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState)
    val state = mutableState.asStateFlow()

    private var requestGeneration = 0L

    fun enterOperations(context: ActiveOperationsContext, permissionHint: TaskVisibilityHint) {
        requestGeneration++
        mutableState.value = WarehouseUiState(
            route = WarehouseRoute.WorkEntry,
            workEntryStatus = when (permissionHint) {
                TaskVisibilityHint.Available -> WorkEntryStatus.TaskAvailable

                TaskVisibilityHint.Unavailable,
                TaskVisibilityHint.Unknown -> WorkEntryStatus.PermissionUnavailable
            },
            permissionHint = permissionHint,
            activeContext = context,
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

    fun openProductSearch() {
        val current = mutableState.value
        if (current.workEntryStatus != WorkEntryStatus.TaskAvailable ||
            current.activeContext == null
        ) {
            return
        }
        requestGeneration++
        mutableState.value = current.copy(
            route = WarehouseRoute.ProductSearch,
            search = ProductSearchUiState(authorityEpoch = current.authorityEpoch),
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
                status = if (value.isBlank()) {
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

    fun submitSearch() {
        val current = mutableState.value
        val search = current.search ?: return
        if (current.route != WarehouseRoute.ProductSearch) return
        val query = search.query.trim()
        if (query.isEmpty()) {
            mutableState.value = current.copy(
                search = search.copy(
                    status = ProductSearchStatus.InvalidQuery,
                    errorMessage = ProductSearchStatus.InvalidQuery
                )
            )
            return
        }
        runSearch(query = query, pageKey = null, append = false)
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
        runSearch(query = search.query.trim(), pageKey = search.nextPageKey, append = true)
    }

    fun retrySearch() {
        val current = mutableState.value
        if (current.route != WarehouseRoute.ProductSearch) return
        val search = current.search ?: return
        if (search.query.isBlank()) return
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
            setOf(ProductSearchStatus.OneCandidate, ProductSearchStatus.MultipleCandidates)
        ) {
            return
        }
        val candidate = search.candidates.firstOrNull { it.key == candidateKey } ?: return
        val context = current.activeContext ?: return
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
            val result = runCatching { gateway.confirm(candidate, epoch, context) }
                .getOrElse { CandidateConfirmationResult.ServiceUnavailable }
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
                            search = mutableState.value.search?.copy(pendingCandidateKey = null),
                            confirmedSku = confirmed.copy(context = context)
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

                CandidateConfirmationResult.PermissionDenied -> updateSearchFailure(
                    ProductSearchStatus.PermissionDenied,
                    generation
                )

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

            WarehouseRoute.WorkEntry -> Unit
        }
    }

    fun authorityReplaced(context: ActiveOperationsContext, permissionHint: TaskVisibilityHint) {
        enterOperations(context, permissionHint)
    }

    fun contextInvalidated() {
        requestGeneration++
        val nextEpoch = mutableState.value.authorityEpoch + 1
        mutableState.value = WarehouseUiState(
            route = WarehouseRoute.WorkEntry,
            workEntryStatus = WorkEntryStatus.ContextInvalidated,
            authorityEpoch = nextEpoch
        )
    }

    fun sessionInvalidated() {
        requestGeneration++
        val nextEpoch = mutableState.value.authorityEpoch + 1
        mutableState.value = WarehouseUiState(
            route = WarehouseRoute.WorkEntry,
            workEntryStatus = WorkEntryStatus.SessionInvalidated,
            authorityEpoch = nextEpoch
        )
    }

    private fun runSearch(query: String, pageKey: String?, append: Boolean) {
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
            val result = runCatching { gateway.search(query, pageKey, epoch) }
                .getOrElse { ProductSearchResult.ServiceUnavailable }
            if (!isCurrent(generation, epoch) || context.authorityEpoch != epoch) return@launch
            when (result) {
                is ProductSearchResult.Page -> {
                    val currentSearch = mutableState.value.search ?: return@launch
                    val items = if (append) {
                        currentSearch.candidates + result.items
                    } else {
                        result.items
                    }
                    val status = when {
                        items.isEmpty() -> ProductSearchStatus.Empty
                        items.size == 1 -> ProductSearchStatus.OneCandidate
                        else -> ProductSearchStatus.MultipleCandidates
                    }
                    mutableState.value = mutableState.value.copy(
                        search = currentSearch.copy(
                            query = previousSearch.query,
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

                ProductSearchResult.PermissionDenied -> updateSearchFailure(
                    ProductSearchStatus.PermissionDenied,
                    generation
                )

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
            it.copy(route = WarehouseRoute.WorkEntry, search = null, confirmedSku = null)
        }
    }

    private fun invalidateContext() {
        contextInvalidated()
    }

    private fun invalidateSession() {
        sessionInvalidated()
    }
}
