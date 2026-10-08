package com.nexa.mobile.operations.salescommitment.presentation.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.creditreceivables.domain.model.commercial.CustomerCredit
import com.nexa.mobile.operations.salescommitment.application.commercial.CustomerProgressGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.CustomerProgressResult
import com.nexa.mobile.operations.salescommitment.application.commercial.ProgressStatus
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.CustomerCommitment
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CustomerProgressState(
    val customerId: String = "",
    val currency: String = "PEN",
    val customerName: String? = null,
    val commitments: List<CustomerCommitment> = emptyList(),
    val credit: CustomerCredit? = null,
    val commitmentsStatus: ProgressStatus = ProgressStatus.Idle,
    val creditStatus: ProgressStatus = ProgressStatus.Idle,
    val page: Int = 0,
    val total: Long? = null,
    val receivedAt: Instant? = null
)

class CustomerProgressViewModel(private val gateway: CustomerProgressGateway) : ViewModel() {
    private val mutableState = MutableStateFlow(CustomerProgressState())
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
            CustomerProgressState(customerId = value.trim(), currency = state.value.currency)
    }
    fun currencyChanged(value: String) {
        generation++
        mutableState.value =
            CustomerProgressState(
                customerId = state.value.customerId,
                currency = value.uppercase().take(3)
            )
    }
    fun refresh() = load(0)
    fun nextPage() {
        val current = state.value
        if ((current.page + 1L) * 25 <
            (current.total ?: 0)
        ) {
            load(current.page + 1)
        }
    }
    fun previousPage() {
        if (state.value.page > 0) load(state.value.page - 1)
    }
    private fun load(page: Int) {
        val captured = authority ?: return
        val input = state.value
        val request = ++generation
        mutableState.value = CustomerProgressState(
            input.customerId,
            input.currency,
            commitmentsStatus = ProgressStatus.Pending,
            creditStatus = ProgressStatus.Pending,
            page = page
        )
        viewModelScope.launch {
            val result = try {
                gateway.read(captured, input.customerId, input.currency, page)
            } catch (
                _: Exception
            ) {
                CustomerProgressResult(
                    null,
                    emptyList(),
                    null,
                    ProgressStatus.Unavailable,
                    ProgressStatus.Unavailable,
                    page,
                    null
                )
            }
            if (request != generation || authority != captured) return@launch
            mutableState.value =
                CustomerProgressState(
                    input.customerId, input.currency, result.customerName,
                    result.commitments, result.credit, result.commitmentsStatus,
                    result.creditStatus, result.page,
                    result.total, Instant.now()
                )
        }
    }
    fun deactivate() {
        generation++
        authority = null
        mutableState.value = CustomerProgressState()
    }
}
