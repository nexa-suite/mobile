package com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse

import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingWorkListCacheProjection
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.PickingWorkPage

/** Local, non-authoritative cache of server-confirmed picking work summaries. */
interface PickingWorkListCache {
    suspend fun load(authority: PickingAuthority, page: Int): PickingWorkListCacheRead

    /** The input must be a page returned by the protected server read. */
    suspend fun saveServerConfirmedPage(
        authority: PickingAuthority,
        sessionEpoch: Long,
        page: PickingWorkPage
    ): PickingWorkListCacheWrite

    /** Persist a denial fence until an authoritative list read succeeds for this exact scope. */
    suspend fun markDenied(
        authority: PickingAuthority,
        sessionEpoch: Long
    ): PickingWorkListCacheWrite
}

sealed interface PickingWorkListCacheRead {
    data class Available(val value: PickingWorkListCacheProjection?) : PickingWorkListCacheRead
    data object Unavailable : PickingWorkListCacheRead
}

enum class PickingWorkListCacheWrite { Saved, Superseded, Unavailable }
