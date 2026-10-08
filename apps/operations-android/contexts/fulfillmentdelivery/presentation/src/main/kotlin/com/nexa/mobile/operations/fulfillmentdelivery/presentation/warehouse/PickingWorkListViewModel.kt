package com.nexa.mobile.operations.fulfillmentdelivery.presentation.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingWorkListResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingWorkListGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads only current server work and drops responses after authority or route changes. */
class PickingWorkListViewModel(private val gateway: PickingWorkListGateway) : ViewModel() {
    private val mutableState = MutableStateFlow(PickingWorkListUiState())
    val state = mutableState.asStateFlow()

    private var authority: PickingAuthority? = null
    private var generation = 0L

    fun activate(currentAuthority: PickingAuthority) {
        authority = currentAuthority
        generation++
        mutableState.value = PickingWorkListUiState(
            status = if (currentAuthority.canRead) {
                PickingWorkListStatus.Loading
            } else {
                PickingWorkListStatus.PermissionDenied
            }
        )
        if (currentAuthority.canRead) loadPage(0, currentAuthority, generation)
    }

    fun reload() {
        val currentAuthority = authority ?: return
        if (currentAuthority.canRead &&
            mutableState.value.status != PickingWorkListStatus.Loading
        ) {
            generation++
            loadPage(mutableState.value.page, currentAuthority, generation)
        }
    }

    fun nextPage() {
        val current = mutableState.value
        if (current.status != PickingWorkListStatus.Ready ||
            (current.page.toLong() + 1) * current.size >= current.totalItems
        ) {
            return
        }
        authority?.let { currentAuthority ->
            generation++
            loadPage(current.page + 1, currentAuthority, generation)
        }
    }

    fun previousPage() {
        val current = mutableState.value
        if (current.page <= 0 || current.status != PickingWorkListStatus.Ready) return
        authority?.let { currentAuthority ->
            generation++
            loadPage(current.page - 1, currentAuthority, generation)
        }
    }

    fun invalidate() {
        generation++
        authority = null
        mutableState.value = PickingWorkListUiState()
    }

    private fun loadPage(page: Int, currentAuthority: PickingAuthority, requestGeneration: Long) {
        mutableState.value = PickingWorkListUiState(
            status = PickingWorkListStatus.Loading,
            page = page
        )
        viewModelScope.launch {
            val result = try {
                gateway.list(currentAuthority, page, WORK_PAGE_SIZE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PickingWorkListResult.ServiceUnavailable
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            mutableState.value = when (result) {
                is PickingWorkListResult.Loaded -> PickingWorkListUiState(
                    status = PickingWorkListStatus.Ready,
                    items = result.value.items,
                    page = result.value.page,
                    size = result.value.size,
                    totalItems = result.value.totalItems,
                    asOf = result.value.asOf
                )

                PickingWorkListResult.NetworkUnavailable -> failure(
                    PickingWorkListStatus.NetworkUnavailable,
                    page
                )

                PickingWorkListResult.ServiceUnavailable -> failure(
                    PickingWorkListStatus.ServiceUnavailable,
                    page
                )

                PickingWorkListResult.PermissionDenied -> failure(
                    PickingWorkListStatus.PermissionDenied,
                    page
                )

                PickingWorkListResult.ContextInvalidated -> failure(
                    PickingWorkListStatus.ContextInvalidated,
                    page
                )

                PickingWorkListResult.SessionInvalidated -> failure(
                    PickingWorkListStatus.SessionInvalidated,
                    page
                )
            }
        }
    }

    private fun isCurrent(requestGeneration: Long, requestedAuthority: PickingAuthority): Boolean =
        generation == requestGeneration && authority == requestedAuthority

    private fun failure(status: PickingWorkListStatus, page: Int) =
        PickingWorkListUiState(status = status, page = page, size = WORK_PAGE_SIZE)

    private companion object {
        const val WORK_PAGE_SIZE = 25
    }
}
