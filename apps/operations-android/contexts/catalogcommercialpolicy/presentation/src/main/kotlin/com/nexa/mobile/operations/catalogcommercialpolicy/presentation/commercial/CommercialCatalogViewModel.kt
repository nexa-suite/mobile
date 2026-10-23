package com.nexa.mobile.operations.catalogcommercialpolicy.presentation.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogFilterChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogFilterKind
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogFilterOptionsResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogResult
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.commercial.CommercialProductChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.commercial.CommercialProductFacts
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.commercial.CommercialCatalogStatus
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CommercialCatalogStatus { Idle, Pending, Current, Unavailable, PermissionDenied }
enum class CommercialCatalogFilterStatus { Idle, Pending, Current, Unavailable, PermissionDenied }

data class CommercialCatalogState(
    val customerId: String = "",
    val query: String = "",
    val brandName: String = "",
    val categoryName: String = "",
    val filterKind: CommercialCatalogFilterKind? = null,
    val filterSearch: String = "",
    val filterOptions: List<CommercialCatalogFilterChoice> = emptyList(),
    val filterPage: Int = 0,
    val nextFilterPage: Int? = null,
    val filterTotal: Long = 0,
    val filterStatus: CommercialCatalogFilterStatus = CommercialCatalogFilterStatus.Idle,
    val choices: List<CommercialProductChoice> = emptyList(),
    val nextPage: String? = null,
    val product: CommercialProductFacts? = null,
    val status: CommercialCatalogStatus = CommercialCatalogStatus.Idle
)

class CommercialCatalogViewModel(private val gateway: CommercialCatalogGateway) : ViewModel() {
    private val mutableState = MutableStateFlow(CommercialCatalogState())
    val state = mutableState.asStateFlow()
    private var authority: CommercialAuthority? = null
    private var generation = 0L
    private var filterGeneration = 0L
    fun activate(value: CommercialAuthority) {
        deactivate()
        authority =
            value.copy(permissions = value.permissions.toSet())
    }
    fun customerIdChanged(value: String) {
        generation++
        mutableState.value = state.value.copy(
            customerId = value.trim(),
            choices = emptyList(),
            nextPage = null,
            product = null,
            status = CommercialCatalogStatus.Idle
        )
    }
    fun queryChanged(value: String) {
        updateCriteria { copy(query = value.take(160)) }
    }
    fun brandChanged(value: String) {
        updateCriteria { copy(brandName = value.take(160)) }
    }
    fun categoryChanged(value: String) {
        updateCriteria { copy(categoryName = value.take(160)) }
    }

    fun openFilterOptions(kind: CommercialCatalogFilterKind) {
        mutableState.value = state.value.copy(
            filterKind = kind,
            filterSearch = "",
            filterOptions = emptyList(),
            filterPage = 0,
            nextFilterPage = null,
            filterTotal = 0,
            filterStatus = CommercialCatalogFilterStatus.Idle
        )
        loadFilterOptions(kind, "", 0, append = false)
    }

    fun searchFilterOptions(kind: CommercialCatalogFilterKind, search: String) {
        mutableState.value = state.value.copy(filterSearch = search.take(120))
    }

    fun loadFilterOptions(
        kind: CommercialCatalogFilterKind,
        search: String,
        page: Int,
        append: Boolean
    ) {
        val current = authority ?: return
        val captured = authority
        val request = ++filterGeneration
        val normalized = search.trim().take(120)
        mutableState.value = state.value.copy(
            filterKind = kind,
            filterSearch = normalized,
            filterOptions = if (append) state.value.filterOptions else emptyList(),
            nextFilterPage = if (append) state.value.nextFilterPage else null,
            filterStatus = CommercialCatalogFilterStatus.Pending
        )
        viewModelScope.launch {
            val result = try {
                gateway.filterOptions(current, kind, normalized, page)
            } catch (_: Exception) {
                CommercialCatalogFilterOptionsResult.Unavailable
            }
            if (request != filterGeneration || authority != captured) return@launch
            mutableState.value = when (result) {
                is CommercialCatalogFilterOptionsResult.Choices -> {
                    val items = if (append) {
                        (state.value.filterOptions + result.items).distinctBy { it.id }
                    } else {
                        result.items
                    }
                    state.value.copy(
                        filterOptions = items,
                        filterPage = result.page,
                        nextFilterPage = (result.page + 1).takeIf {
                            (it.toLong() * result.size) < result.total
                        },
                        filterTotal = result.total,
                        filterStatus = CommercialCatalogFilterStatus.Current
                    )
                }

                CommercialCatalogFilterOptionsResult.PermissionDenied -> state.value.copy(
                    filterOptions = emptyList(),
                    nextFilterPage = null,
                    filterStatus = CommercialCatalogFilterStatus.PermissionDenied
                )

                CommercialCatalogFilterOptionsResult.Unavailable -> state.value.copy(
                    filterOptions = emptyList(),
                    nextFilterPage = null,
                    filterStatus = CommercialCatalogFilterStatus.Unavailable
                )
            }
        }
    }

    fun selectFilterOption(
        kind: CommercialCatalogFilterKind,
        choice: CommercialCatalogFilterChoice?
    ) {
        if (choice != null && state.value.filterOptions.none { it.id == choice.id }) return
        when (kind) {
            CommercialCatalogFilterKind.BRAND -> brandChanged(choice?.name.orEmpty())
            CommercialCatalogFilterKind.CATEGORY -> categoryChanged(choice?.name.orEmpty())
        }
    }
    private fun updateCriteria(change: CommercialCatalogState.() -> CommercialCatalogState) {
        generation++
        mutableState.value = state.value.change().copy(
            choices = emptyList(),
            nextPage = null,
            product = null,
            status = CommercialCatalogStatus.Idle
        )
    }
    fun search() = load(null)
    fun nextPage() {
        state.value.nextPage?.let(::load)
    }
    private fun load(page: String?) {
        val current = authority ?: return
        val input = state.value
        perform(input) {
            gateway.search(
                current,
                input.customerId,
                input.query,
                input.brandName,
                input.categoryName,
                page
            )
        }
    }
    fun selectProduct(id: String) {
        val current = authority ?: return
        val input = state.value
        if (input.status != CommercialCatalogStatus.Current ||
            input.choices.none { it.id == id }
        ) {
            return
        }
        perform(input) { gateway.detail(current, input.customerId, id) }
    }
    private fun perform(
        input: CommercialCatalogState,
        action: suspend () -> CommercialCatalogResult
    ) {
        val captured = authority ?: return
        val request = ++generation
        mutableState.value =
            input.copy(
                product = null,
                choices = emptyList(),
                status = CommercialCatalogStatus.Pending
            )
        viewModelScope.launch {
            val result = try {
                action()
            } catch (
                _: Exception
            ) {
                CommercialCatalogResult.Unavailable
            }
            if (request != generation || authority != captured) return@launch
            mutableState.value = when (result) {
                is CommercialCatalogResult.Choices -> input.copy(
                    choices = result.items,
                    nextPage = result.nextPage,
                    product = null,
                    status = CommercialCatalogStatus.Current
                )

                is CommercialCatalogResult.Product -> input.copy(
                    product = result.value,
                    status = CommercialCatalogStatus.Current
                )

                CommercialCatalogResult.PermissionDenied -> input.copy(
                    choices = emptyList(),
                    product = null,
                    nextPage = null,
                    status = CommercialCatalogStatus.PermissionDenied
                )

                CommercialCatalogResult.Unavailable -> input.copy(
                    choices = emptyList(),
                    product = null,
                    nextPage = null,
                    status = CommercialCatalogStatus.Unavailable
                )
            }
        }
    }
    fun deactivate() {
        generation++
        filterGeneration++
        authority = null
        mutableState.value = CommercialCatalogState()
    }
}
