package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import com.nexa.mobile.operations.feature.warehouse.model.FulfillmentPickingSnapshot
import com.nexa.mobile.operations.feature.warehouse.model.PickingAuthority
import com.nexa.mobile.operations.feature.warehouse.model.PickingWorkItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local work organization only. Each physical action uses its existing server-authorized workflow. */
data class WarehouseBatchItem(
    val prepared: PickingWorkItem,
    val confirmed: FulfillmentPickingSnapshot? = null,
    val reviewNote: String? = null
)

data class WarehouseBatchUiState(
    val authorityEpoch: Long = 0,
    val items: List<WarehouseBatchItem> = emptyList(),
    val selectedId: String? = null
)

class WarehouseBatchViewModel : ViewModel() {
    private var authority: PickingAuthority? = null
    private val mutableState = MutableStateFlow(WarehouseBatchUiState())
    val state = mutableState.asStateFlow()

    fun activate(value: PickingAuthority) {
        authority = value
        mutableState.value = WarehouseBatchUiState(authorityEpoch = value.authorityEpoch)
    }

    fun add(item: PickingWorkItem, currentPage: PickingWorkListUiState) {
        if (authority?.canRead != true || currentPage.status != PickingWorkListStatus.Ready ||
            currentPage.items.none { it == item } || mutableState.value.items.size >= 25 ||
            mutableState.value.items.any { it.prepared.fulfillmentId == item.fulfillmentId }
        ) {
            return
        }
        mutableState.value =
            mutableState.value.copy(items = mutableState.value.items + WarehouseBatchItem(item))
    }

    fun move(id: String, offset: Int) {
        val current = mutableState.value
        if (current.selectedId != null) return
        val index = current.items.indexOfFirst { it.prepared.fulfillmentId == id }
        val destination = index + offset
        if (index < 0 || destination !in current.items.indices) return
        val reordered = current.items.toMutableList()
        val item = reordered.removeAt(index)
        reordered.add(destination, item)
        mutableState.value = current.copy(items = reordered)
    }

    fun select(id: String): Boolean {
        val current = mutableState.value
        if (authority?.canRead != true ||
            current.items.none { it.prepared.fulfillmentId == id }
        ) {
            return false
        }
        mutableState.value = current.copy(selectedId = id)
        return true
    }

    fun observe(value: PickingUiState) {
        val current = mutableState.value
        val result = value.confirmedFulfillment ?: return
        if (value.authorityEpoch != current.authorityEpoch || current.selectedId != result.id ||
            value.command != PickingCommandStatus.Confirmed
        ) {
            return
        }
        mutableState.value = current.copy(
            items = current.items.map {
                if (it.prepared.fulfillmentId == result.id) it.copy(confirmed = result) else it
            }
        )
    }

    fun review(id: String, note: String) {
        val current = mutableState.value
        if (authority == null || note.isBlank()) return
        mutableState.value = current.copy(
            items = current.items.map {
                if (it.prepared.fulfillmentId ==
                    id
                ) {
                    it.copy(reviewNote = note.trim().take(500))
                } else {
                    it
                }
            }
        )
    }

    fun closeItem() {
        mutableState.value = mutableState.value.copy(selectedId = null)
    }
    fun invalidate() {
        authority = null
        mutableState.value = WarehouseBatchUiState()
    }
}
