package com.nexa.mobile.operations.feature.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.feature.commercial.application.CustomerGateway
import com.nexa.mobile.operations.feature.commercial.application.CustomerResult
import com.nexa.mobile.operations.feature.commercial.model.CommercialAuthority
import com.nexa.mobile.operations.feature.commercial.model.CustomerRelationship
import com.nexa.mobile.operations.feature.commercial.model.CustomerSearchStatus
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CustomerSearchState(
    val query: String = "",
    val items: List<CustomerRelationship> = emptyList(),
    val detail: CustomerRelationship? = null,
    val page: Int = 0,
    val total: Long? = null,
    val status: CustomerSearchStatus = CustomerSearchStatus.Idle,
    val receivedAt: Instant? = null
)

class CustomerSearchViewModel(private val gateway: CustomerGateway) : ViewModel() {
    private val mutableState = MutableStateFlow(CustomerSearchState())
    val state: StateFlow<CustomerSearchState> = mutableState.asStateFlow()
    private var authority: CommercialAuthority? = null
    private var generation = 0L

    fun activate(value: CommercialAuthority) {
        deactivate()
        if (!value.canReadCustomers) {
            mutableState.value = CustomerSearchState(status = CustomerSearchStatus.PermissionDenied)
            return
        }
        authority = value.copy(permissions = value.permissions.toSet())
        search()
    }
    fun queryChanged(value: String) {
        generation++
        mutableState.value = CustomerSearchState(query = value.take(200))
    }
    fun search() = loadPage(0)
    fun nextPage() {
        val current = state.value
        if (current.status == CustomerSearchStatus.Current &&
            (current.page + 1L) * 25 < (current.total ?: 0)
        ) {
            loadPage(current.page + 1)
        }
    }
    fun previousPage() {
        if (state.value.page > 0) loadPage(state.value.page - 1)
    }
    private fun loadPage(page: Int) {
        val captured = authority ?: return
        val request = ++generation
        val query = state.value.query
        mutableState.value =
            CustomerSearchState(query = query, page = page, status = CustomerSearchStatus.Loading)
        viewModelScope.launch {
            val result = try {
                gateway.search(captured, query, page)
            } catch (
                _: Exception
            ) {
                CustomerResult.Unavailable
            }
            if (request != generation || authority != captured) return@launch
            mutableState.value = when (result) {
                is CustomerResult.Page -> CustomerSearchState(
                    query,
                    result.items,
                    page = result.page,
                    total = result.total,
                    status = CustomerSearchStatus.Current,
                    receivedAt = Instant.now()
                )

                CustomerResult.PermissionDenied -> CustomerSearchState(
                    query,
                    status = CustomerSearchStatus.PermissionDenied
                )

                else -> CustomerSearchState(query, status = CustomerSearchStatus.Unavailable)
            }
        }
    }
    fun selectCustomer(id: String) {
        val captured = authority ?: return
        if (state.value.status != CustomerSearchStatus.Current ||
            state.value.items.none { it.id == id }
        ) {
            return
        }
        val request = ++generation
        mutableState.value =
            state.value.copy(
                detail = null,
                status = CustomerSearchStatus.Loading,
                receivedAt = null
            )
        viewModelScope.launch {
            val result = try {
                gateway.detail(captured, id)
            } catch (
                _: Exception
            ) {
                CustomerResult.Unavailable
            }
            if (request != generation || authority != captured) return@launch
            mutableState.value = when (result) {
                is CustomerResult.Detail -> state.value.copy(
                    detail = result.customer,
                    status = CustomerSearchStatus.Current,
                    receivedAt = Instant.now()
                )

                CustomerResult.PermissionDenied -> CustomerSearchState(
                    query = state.value.query,
                    status = CustomerSearchStatus.PermissionDenied
                )

                else -> CustomerSearchState(
                    query = state.value.query,
                    status = CustomerSearchStatus.Unavailable
                )
            }
        }
    }
    fun deactivate() {
        generation++
        authority = null
        mutableState.value = CustomerSearchState()
    }
}
