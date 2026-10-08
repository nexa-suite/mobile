package com.nexa.mobile.operations.catalogcommercialpolicy.presentation.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogResult
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.commercial.CommercialAuthority
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.commercial.CommercialCatalogStatus
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.commercial.CommercialProductChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.commercial.CommercialProductFacts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CommercialCatalogStatus { Idle, Pending, Current, Unavailable, PermissionDenied }

data class CommercialCatalogState(
    val customerId: String = "",
    val query: String = "",
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
    fun activate(value: CommercialAuthority) {
        deactivate()
        authority =
            value.copy(permissions = value.permissions.toSet())
    }
    fun customerIdChanged(value: String) {
        generation++
        mutableState.value =
            CommercialCatalogState(customerId = value.trim(), query = state.value.query)
    }
    fun queryChanged(value: String) {
        generation++
        mutableState.value =
            CommercialCatalogState(customerId = state.value.customerId, query = value.take(160))
    }
    fun search() = load(null)
    fun nextPage() {
        state.value.nextPage?.let(::load)
    }
    private fun load(page: String?) {
        val current = authority ?: return
        val input = state.value
        perform(input) { gateway.search(current, input.customerId, input.query, page) }
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
        authority = null
        mutableState.value = CommercialCatalogState()
    }
}
