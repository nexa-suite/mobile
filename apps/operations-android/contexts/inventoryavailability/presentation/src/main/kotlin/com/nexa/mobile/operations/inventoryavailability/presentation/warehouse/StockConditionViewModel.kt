package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockConditionGatewayResult
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockConditionGateway
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockConditionLot
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.ActiveOperationsContext
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Read-only controller. It clears facts on context/session changes and fences late responses. */
class StockConditionViewModel(
    private val gateway: StockConditionGateway,
    private val now: () -> Instant = Instant::now,
    private val stockConditionChangeSignals: Flow<Unit> = emptyFlow()
) : ViewModel() {
    private val mutableState = MutableStateFlow(StockConditionUiState())
    val state = mutableState.asStateFlow()
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    private var activeContext: ActiveOperationsContext? = null
    private var generation = 0L
    private var listRequestGeneration = 0L
    private var detailRequestGeneration = 0L

    init {
        viewModelScope.launch {
            refreshRequests.receiveAsFlow().collect { refreshList() }
        }
    }

    fun activate(context: ActiveOperationsContext) {
        if (activeContext == context && mutableState.value.status !in INVALIDATED_STATUS) return
        generation++
        listRequestGeneration++
        detailRequestGeneration++
        activeContext = context
        mutableState.value = StockConditionUiState(
            authorityEpoch = context.authorityEpoch,
            stockConditionChangeSignals = if (
                context.readPermissionHint() == StockConditionReadHint.Available
            ) {
                stockConditionChangeSignals
            } else {
                emptyFlow()
            }
        )
        refresh()
    }

    fun refresh() {
        val context = activeContext ?: return
        if (mutableState.value.status in REFRESH_BLOCKED_STATUSES) return
        when (context.readPermissionHint()) {
            StockConditionReadHint.Unknown -> {
                generation++
                listRequestGeneration++
                detailRequestGeneration++
                mutableState.value = StockConditionUiState(
                    authorityEpoch = context.authorityEpoch,
                    status = StockConditionStatus.PermissionUnknown
                )
                return
            }

            StockConditionReadHint.Unavailable -> {
                generation++
                listRequestGeneration++
                detailRequestGeneration++
                mutableState.value = StockConditionUiState(
                    authorityEpoch = context.authorityEpoch,
                    status = StockConditionStatus.PermissionDenied
                )
                return
            }

            StockConditionReadHint.Available -> Unit
        }

        mutableState.update { current ->
            if (current.authorityEpoch == context.authorityEpoch) {
                current.copy(status = StockConditionStatus.Loading)
            } else {
                current
            }
        }
        refreshRequests.trySend(Unit)
    }

    private suspend fun refreshList() {
        val context = activeContext ?: return
        if (mutableState.value.status in REFRESH_BLOCKED_STATUSES) return
        when (context.readPermissionHint()) {
            StockConditionReadHint.Unknown -> {
                generation++
                listRequestGeneration++
                detailRequestGeneration++
                mutableState.value = StockConditionUiState(
                    authorityEpoch = context.authorityEpoch,
                    status = StockConditionStatus.PermissionUnknown
                )
                return
            }

            StockConditionReadHint.Unavailable -> {
                generation++
                listRequestGeneration++
                detailRequestGeneration++
                mutableState.value = StockConditionUiState(
                    authorityEpoch = context.authorityEpoch,
                    status = StockConditionStatus.PermissionDenied
                )
                return
            }

            StockConditionReadHint.Available -> Unit
        }

        val authorityGeneration = generation
        val request = ++listRequestGeneration
        mutableState.update { current ->
            if (current.authorityEpoch == context.authorityEpoch) {
                current.copy(status = StockConditionStatus.Loading)
            } else {
                current
            }
        }
        val result = try {
            gateway.lots(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            StockConditionGatewayResult.ServiceUnavailable
        }
        if (!isCurrentList(request, authorityGeneration, context)) return
        when (result) {
            is StockConditionGatewayResult.Lots -> {
                val duplicateIds = result.items.map { it.id.lowercase() }.let {
                    it.size != it.toSet().size
                }
                if (duplicateIds) {
                    setListFailure(context, StockConditionStatus.ServiceUnavailable)
                } else {
                    val current = mutableState.value
                    val selectedLot = current.selectedLotId?.let { selectedId ->
                        result.items.firstOrNull { it.id.equals(selectedId, ignoreCase = true) }
                    }
                    val retainedDetail = selectedLot != null && current.selectedLot?.matches(
                        selectedLot
                    ) == true
                    mutableState.value = current.copy(
                        status = if (result.items.isEmpty()) {
                            StockConditionStatus.Empty
                        } else {
                            StockConditionStatus.Current
                        },
                        lots = result.items,
                        listObservedAt = now(),
                        selectedLotId = selectedLot?.id,
                        selectedLot = current.selectedLot.takeIf { retainedDetail },
                        detailStatus = if (selectedLot == null) {
                            StockConditionDetailStatus.NotRequested
                        } else {
                            StockConditionDetailStatus.Loading
                        },
                        detailObservedAt = current.detailObservedAt.takeIf { retainedDetail },
                        availability = current.availability.takeIf {
                            retainedDetail && current.selectedLot.catalogItemId ==
                                selectedLot.catalogItemId
                        },
                        availabilityStatus = when {
                            selectedLot == null -> StockConditionAvailabilityStatus.NotRequested

                            selectedLot.catalogItemId.isNullOrBlank() ->
                                StockConditionAvailabilityStatus.Unavailable

                            else -> StockConditionAvailabilityStatus.Loading
                        }
                    )
                    if (selectedLot == null) {
                        detailRequestGeneration++
                    } else {
                        val detailRequest = ++detailRequestGeneration
                        refreshSelectedLot(
                            context,
                            selectedLot,
                            detailRequest,
                            authorityGeneration
                        )
                    }
                }
            }

            StockConditionGatewayResult.NetworkUnavailable -> setListFailure(
                context,
                StockConditionStatus.NetworkUnavailable
            )

            StockConditionGatewayResult.ServiceUnavailable -> setListFailure(
                context,
                StockConditionStatus.ServiceUnavailable
            )

            StockConditionGatewayResult.PermissionDenied -> setListFailure(
                context,
                StockConditionStatus.PermissionDenied
            )

            StockConditionGatewayResult.ContextInvalidated -> invalidateContext()

            StockConditionGatewayResult.SessionInvalidated -> invalidateSession()

            is StockConditionGatewayResult.Lot,
            is StockConditionGatewayResult.Availability -> setListFailure(
                context,
                StockConditionStatus.ServiceUnavailable
            )
        }
    }

    fun selectLot(lotId: String) {
        val context = activeContext ?: return
        val current = mutableState.value
        if (current.status != StockConditionStatus.Current ||
            current.lots.none { it.id.equals(lotId, ignoreCase = true) }
        ) {
            return
        }
        val summary = current.lots.first { it.id.equals(lotId, ignoreCase = true) }
        val request = ++detailRequestGeneration
        val authorityGeneration = generation
        mutableState.value = current.copy(
            selectedLotId = summary.id,
            selectedLot = null,
            detailStatus = StockConditionDetailStatus.Loading,
            detailObservedAt = null,
            availability = null,
            availabilityStatus = if (summary.catalogItemId.isNullOrBlank()) {
                StockConditionAvailabilityStatus.Unavailable
            } else {
                StockConditionAvailabilityStatus.Loading
            }
        )
        viewModelScope.launch {
            refreshSelectedLot(context, summary, request, authorityGeneration)
        }
    }

    private suspend fun refreshSelectedLot(
        context: ActiveOperationsContext,
        summary: StockConditionLot,
        request: Long,
        authorityGeneration: Long
    ) {
        mutableState.update { current ->
            if (current.selectedLotId.equals(summary.id, ignoreCase = true)) {
                current.copy(
                    detailStatus = StockConditionDetailStatus.Loading,
                    availabilityStatus = if (summary.catalogItemId.isNullOrBlank()) {
                        StockConditionAvailabilityStatus.Unavailable
                    } else {
                        StockConditionAvailabilityStatus.Loading
                    }
                )
            } else {
                current
            }
        }
        val detail = safeCall { gateway.lot(summary.id, context) }
        if (!isCurrentDetail(request, authorityGeneration, context)) return
        when (detail) {
            is StockConditionGatewayResult.Lot -> {
                val item = detail.item
                if (!item.matches(summary)) {
                    setDetailFailure(StockConditionDetailStatus.ServiceUnavailable)
                    return
                }
                mutableState.update {
                    it.copy(
                        selectedLot = item,
                        detailStatus = StockConditionDetailStatus.Current,
                        detailObservedAt = now()
                    )
                }
                val catalogItemId = item.catalogItemId
                if (catalogItemId.isNullOrBlank()) {
                    mutableState.update {
                        it.copy(
                            availability = null,
                            availabilityStatus = StockConditionAvailabilityStatus.Unavailable
                        )
                    }
                    return
                }
                val availabilityResult = safeCall {
                    gateway.availability(item.warehouseId, catalogItemId, context)
                }
                if (!isCurrentDetail(request, authorityGeneration, context)) return
                when (availabilityResult) {
                    is StockConditionGatewayResult.Availability -> {
                        val value = availabilityResult.item
                        val valid = value == null || value.catalogItemId == catalogItemId
                        if (valid) {
                            mutableState.update {
                                it.copy(
                                    availability = value,
                                    availabilityStatus = if (value != null) {
                                        StockConditionAvailabilityStatus.Current
                                    } else {
                                        StockConditionAvailabilityStatus.Unavailable
                                    }
                                )
                            }
                        } else {
                            setAvailabilityFailure(
                                StockConditionAvailabilityStatus.ServiceUnavailable
                            )
                        }
                    }

                    StockConditionGatewayResult.NetworkUnavailable -> setAvailabilityFailure(
                        StockConditionAvailabilityStatus.NetworkUnavailable
                    )

                    StockConditionGatewayResult.ServiceUnavailable -> setAvailabilityFailure(
                        StockConditionAvailabilityStatus.ServiceUnavailable
                    )

                    StockConditionGatewayResult.PermissionDenied -> clearOnDenied(
                        context,
                        StockConditionDetailStatus.PermissionDenied
                    )

                    StockConditionGatewayResult.ContextInvalidated -> invalidateContext()

                    StockConditionGatewayResult.SessionInvalidated -> invalidateSession()

                    is StockConditionGatewayResult.Lots,
                    is StockConditionGatewayResult.Lot -> setAvailabilityFailure(
                        StockConditionAvailabilityStatus.ServiceUnavailable
                    )
                }
            }

            StockConditionGatewayResult.NetworkUnavailable -> setDetailFailure(
                StockConditionDetailStatus.NetworkUnavailable
            )

            StockConditionGatewayResult.ServiceUnavailable -> setDetailFailure(
                StockConditionDetailStatus.ServiceUnavailable
            )

            StockConditionGatewayResult.PermissionDenied -> clearOnDenied(
                context,
                StockConditionDetailStatus.PermissionDenied
            )

            StockConditionGatewayResult.ContextInvalidated -> invalidateContext()

            StockConditionGatewayResult.SessionInvalidated -> invalidateSession()

            is StockConditionGatewayResult.Lots,
            is StockConditionGatewayResult.Availability -> setDetailFailure(
                StockConditionDetailStatus.ServiceUnavailable
            )
        }
    }

    /** Route exit clears displayed facts; reopening always needs a new protected list read. */
    fun deactivate() {
        generation++
        listRequestGeneration++
        detailRequestGeneration++
        activeContext = null
        mutableState.value = StockConditionUiState()
    }

    fun invalidateContext() {
        generation++
        listRequestGeneration++
        detailRequestGeneration++
        activeContext = null
        val epoch = mutableState.value.authorityEpoch
        mutableState.value = StockConditionUiState(
            authorityEpoch = epoch,
            status = StockConditionStatus.ContextInvalidated
        )
    }

    fun invalidateSession() {
        generation++
        listRequestGeneration++
        detailRequestGeneration++
        activeContext = null
        val epoch = mutableState.value.authorityEpoch
        mutableState.value = StockConditionUiState(
            authorityEpoch = epoch,
            status = StockConditionStatus.SessionInvalidated
        )
    }

    private fun setListFailure(context: ActiveOperationsContext, status: StockConditionStatus) {
        if (status == StockConditionStatus.PermissionDenied) {
            generation++
            listRequestGeneration++
            detailRequestGeneration++
            mutableState.value = StockConditionUiState(
                authorityEpoch = context.authorityEpoch,
                status = status
            )
        } else {
            mutableState.update { current ->
                if (current.authorityEpoch == context.authorityEpoch) {
                    current.copy(
                        status = status,
                        lots = emptyList(),
                        listObservedAt = null,
                        selectedLotId = null,
                        selectedLot = null,
                        detailStatus = StockConditionDetailStatus.NotRequested,
                        detailObservedAt = null,
                        availability = null,
                        availabilityStatus = StockConditionAvailabilityStatus.NotRequested
                    )
                } else {
                    current
                }
            }
        }
    }

    private fun setDetailFailure(status: StockConditionDetailStatus) {
        mutableState.value = mutableState.value.copy(
            detailStatus = status,
            availabilityStatus = when (status) {
                StockConditionDetailStatus.NetworkUnavailable ->
                    StockConditionAvailabilityStatus.NetworkUnavailable

                StockConditionDetailStatus.ServiceUnavailable ->
                    StockConditionAvailabilityStatus.ServiceUnavailable

                else -> StockConditionAvailabilityStatus.NotRequested
            }
        )
    }

    private fun setAvailabilityFailure(status: StockConditionAvailabilityStatus) {
        mutableState.value = mutableState.value.copy(
            availabilityStatus = status
        )
    }

    private fun clearOnDenied(
        context: ActiveOperationsContext,
        detailStatus: StockConditionDetailStatus
    ) {
        activeContext = null
        generation++
        listRequestGeneration++
        detailRequestGeneration++
        mutableState.value = StockConditionUiState(
            authorityEpoch = context.authorityEpoch,
            status = StockConditionStatus.PermissionDenied,
            detailStatus = detailStatus,
            availabilityStatus = StockConditionAvailabilityStatus.PermissionDenied
        )
    }

    private suspend fun safeCall(
        block: suspend () -> StockConditionGatewayResult
    ): StockConditionGatewayResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        StockConditionGatewayResult.ServiceUnavailable
    }

    private fun isCurrentList(
        request: Long,
        authorityGeneration: Long,
        context: ActiveOperationsContext
    ): Boolean = request == listRequestGeneration && authorityGeneration == generation &&
        activeContext == context && mutableState.value.authorityEpoch == context.authorityEpoch

    private fun isCurrentDetail(
        request: Long,
        authorityGeneration: Long,
        context: ActiveOperationsContext
    ): Boolean = request == detailRequestGeneration && authorityGeneration == generation &&
        activeContext == context &&
        mutableState.value.authorityEpoch == context.authorityEpoch

    private fun StockConditionLot.matches(summary: StockConditionLot): Boolean =
        id.equals(summary.id, ignoreCase = true) &&
            warehouseId.equals(summary.warehouseId, ignoreCase = true) &&
            zoneId.equals(summary.zoneId, ignoreCase = true) &&
            catalogItemId == summary.catalogItemId && skuId.equals(summary.skuId, ignoreCase = true)

    private enum class StockConditionReadHint { Available, Unavailable, Unknown }

    private fun ActiveOperationsContext.readPermissionHint(): StockConditionReadHint {
        val identity = verifiedIdentity ?: return StockConditionReadHint.Unknown
        if (authorityEpoch <= 0 || listOf(
                identity.userId,
                identity.tenantId,
                identity.workspaceId,
                identity.membershipId
            ).any(String::isBlank)
        ) {
            return StockConditionReadHint.Unknown
        }
        val permissions = identity.permissions
        return when {
            permissions.isEmpty() -> StockConditionReadHint.Unknown
            permissions.any { it in STOCK_READ_PERMISSIONS } -> StockConditionReadHint.Available
            else -> StockConditionReadHint.Unavailable
        }
    }

    private companion object {
        val STOCK_READ_PERMISSIONS = setOf("warehouse.read", "inventory.read", "warehouse:read")
        val INVALIDATED_STATUS = setOf(
            StockConditionStatus.ContextInvalidated,
            StockConditionStatus.SessionInvalidated
        )
        val REFRESH_BLOCKED_STATUSES = setOf(
            StockConditionStatus.PermissionUnknown,
            StockConditionStatus.PermissionDenied,
            StockConditionStatus.ContextInvalidated,
            StockConditionStatus.SessionInvalidated
        )
    }
}
