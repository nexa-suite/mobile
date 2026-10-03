package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Read-only controller. It clears facts on context/session changes and fences late responses. */
class StockConditionViewModel(
    private val gateway: StockConditionGateway,
    private val now: () -> Instant = Instant::now
) : ViewModel() {
    private val mutableState = MutableStateFlow(StockConditionUiState())
    val state = mutableState.asStateFlow()

    private var activeContext: ActiveOperationsContext? = null
    private var generation = 0L

    fun activate(context: ActiveOperationsContext) {
        if (activeContext == context && mutableState.value.status !in INVALIDATED_STATUS) return
        generation++
        activeContext = context
        mutableState.value = StockConditionUiState(authorityEpoch = context.authorityEpoch)
        refresh()
    }

    fun refresh() {
        val context = activeContext ?: return
        when (context.readPermissionHint()) {
            StockConditionReadHint.Unknown -> {
                generation++
                mutableState.value = StockConditionUiState(
                    authorityEpoch = context.authorityEpoch,
                    status = StockConditionStatus.PermissionUnknown
                )
                return
            }

            StockConditionReadHint.Unavailable -> {
                generation++
                mutableState.value = StockConditionUiState(
                    authorityEpoch = context.authorityEpoch,
                    status = StockConditionStatus.PermissionDenied
                )
                return
            }

            StockConditionReadHint.Available -> Unit
        }

        val request = ++generation
        mutableState.value = StockConditionUiState(
            authorityEpoch = context.authorityEpoch,
            status = StockConditionStatus.Loading
        )
        viewModelScope.launch {
            val result = try {
                gateway.lots(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                StockConditionGatewayResult.ServiceUnavailable
            }
            if (!isCurrent(request, context)) return@launch
            when (result) {
                is StockConditionGatewayResult.Lots -> {
                    val duplicateIds = result.items.map { it.id.lowercase() }.let {
                        it.size != it.toSet().size
                    }
                    if (duplicateIds) {
                        mutableState.value = StockConditionUiState(
                            authorityEpoch = context.authorityEpoch,
                            status = StockConditionStatus.ServiceUnavailable
                        )
                    } else {
                        mutableState.value = StockConditionUiState(
                            authorityEpoch = context.authorityEpoch,
                            status = if (result.items.isEmpty()) {
                                StockConditionStatus.Empty
                            } else {
                                StockConditionStatus.Current
                            },
                            lots = result.items,
                            listObservedAt = now()
                        )
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
        val request = ++generation
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
            val detail = safeCall { gateway.lot(summary.id, context) }
            if (!isCurrent(request, context)) return@launch
            when (detail) {
                is StockConditionGatewayResult.Lot -> {
                    val item = detail.item
                    if (!item.matches(summary)) {
                        mutableState.value = mutableState.value.copy(
                            detailStatus = StockConditionDetailStatus.ServiceUnavailable,
                            selectedLot = null,
                            availability = null,
                            availabilityStatus = StockConditionAvailabilityStatus.ServiceUnavailable
                        )
                        return@launch
                    }
                    mutableState.value = mutableState.value.copy(
                        selectedLot = item,
                        detailStatus = StockConditionDetailStatus.Current,
                        detailObservedAt = now()
                    )
                    val catalogItemId = item.catalogItemId
                    if (catalogItemId.isNullOrBlank()) {
                        mutableState.value = mutableState.value.copy(
                            availabilityStatus = StockConditionAvailabilityStatus.Unavailable
                        )
                        return@launch
                    }
                    val availabilityResult = safeCall {
                        gateway.availability(item.warehouseId, catalogItemId, context)
                    }
                    if (!isCurrent(request, context)) return@launch
                    when (availabilityResult) {
                        is StockConditionGatewayResult.Availability -> {
                            val value = availabilityResult.item
                            val valid = value == null || value.catalogItemId == catalogItemId
                            mutableState.value = mutableState.value.copy(
                                availability = value.takeIf { valid },
                                availabilityStatus = if (valid && value != null) {
                                    StockConditionAvailabilityStatus.Current
                                } else if (valid) {
                                    StockConditionAvailabilityStatus.Unavailable
                                } else {
                                    StockConditionAvailabilityStatus.ServiceUnavailable
                                }
                            )
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
    }

    /** Route exit clears displayed facts; reopening always needs a new protected list read. */
    fun deactivate() {
        generation++
        activeContext = null
        mutableState.value = StockConditionUiState()
    }

    fun invalidateContext() {
        generation++
        activeContext = null
        val epoch = mutableState.value.authorityEpoch
        mutableState.value = StockConditionUiState(
            authorityEpoch = epoch,
            status = StockConditionStatus.ContextInvalidated
        )
    }

    fun invalidateSession() {
        generation++
        activeContext = null
        val epoch = mutableState.value.authorityEpoch
        mutableState.value = StockConditionUiState(
            authorityEpoch = epoch,
            status = StockConditionStatus.SessionInvalidated
        )
    }

    private fun setListFailure(context: ActiveOperationsContext, status: StockConditionStatus) {
        mutableState.value = StockConditionUiState(
            authorityEpoch = context.authorityEpoch,
            status = status
        )
    }

    private fun setDetailFailure(status: StockConditionDetailStatus) {
        mutableState.value = mutableState.value.copy(
            selectedLot = null,
            detailStatus = status,
            detailObservedAt = null,
            availability = null,
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
            availability = null,
            availabilityStatus = status
        )
    }

    private fun clearOnDenied(
        context: ActiveOperationsContext,
        detailStatus: StockConditionDetailStatus
    ) {
        activeContext = null
        generation++
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

    private fun isCurrent(request: Long, context: ActiveOperationsContext): Boolean =
        request == generation && activeContext == context &&
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
    }
}
