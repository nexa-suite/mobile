package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchReadinessGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchReadinessGatewayResult
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DispatchReadinessViewModel(
    private val gateway: DispatchReadinessGateway,
    private val now: () -> Instant = Instant::now
) : ViewModel() {
    private val mutableState = MutableStateFlow(DispatchReadinessUiState())
    val state = mutableState.asStateFlow()

    private var activeContext: DispatchAuthorityContext? = null
    private var generation = 0L

    fun activate(context: DispatchAuthorityContext) {
        if (activeContext == context && mutableState.value.status !in INVALIDATED_STATUS) return
        generation++
        activeContext = context
        mutableState.value = DispatchReadinessUiState(authorityEpoch = context.authorityEpoch)
        refresh()
    }

    fun refresh() {
        val context = activeContext ?: return
        when (context.readPermissionHint()) {
            PermissionHint.Unknown -> {
                generation++
                mutableState.value = DispatchReadinessUiState(
                    authorityEpoch = context.authorityEpoch,
                    status = DispatchReadinessStatus.PermissionUnknown
                )
                return
            }

            PermissionHint.Unavailable -> {
                generation++
                mutableState.value = DispatchReadinessUiState(
                    authorityEpoch = context.authorityEpoch,
                    status = DispatchReadinessStatus.PermissionDenied
                )
                return
            }

            PermissionHint.Available -> Unit
        }

        val request = ++generation
        mutableState.value = DispatchReadinessUiState(
            authorityEpoch = context.authorityEpoch,
            status = DispatchReadinessStatus.Loading
        )
        viewModelScope.launch {
            val result = safeCall { gateway.list(context) }
            if (!isCurrent(request, context)) return@launch
            when (result) {
                is DispatchReadinessGatewayResult.ListResult -> {
                    val ids = result.items.map { it.fulfillmentId.lowercase() }
                    val malformed = ids.size != ids.toSet().size || result.items.any {
                        it.subjectKind != PREPARED_FULFILLMENT
                    }
                    if (malformed) {
                        mutableState.value = DispatchReadinessUiState(
                            authorityEpoch = context.authorityEpoch,
                            status = DispatchReadinessStatus.ServiceUnavailable
                        )
                    } else {
                        mutableState.value = DispatchReadinessUiState(
                            authorityEpoch = context.authorityEpoch,
                            status = if (result.items.isEmpty()) {
                                DispatchReadinessStatus.Empty
                            } else {
                                DispatchReadinessStatus.Current
                            },
                            items = result.items,
                            asOf = result.asOf,
                            observedAt = now()
                        )
                    }
                }

                DispatchReadinessGatewayResult.NetworkUnavailable -> failList(
                    context,
                    DispatchReadinessStatus.NetworkUnavailable
                )

                DispatchReadinessGatewayResult.ServiceUnavailable -> failList(
                    context,
                    DispatchReadinessStatus.ServiceUnavailable
                )

                DispatchReadinessGatewayResult.PermissionDenied -> failList(
                    context,
                    DispatchReadinessStatus.PermissionDenied
                )

                DispatchReadinessGatewayResult.ContextInvalidated -> invalidateContext()

                DispatchReadinessGatewayResult.SessionInvalidated -> invalidateSession()

                is DispatchReadinessGatewayResult.Detail -> failList(
                    context,
                    DispatchReadinessStatus.ServiceUnavailable
                )
            }
        }
    }

    fun selectFulfillment(fulfillmentId: String) {
        val context = activeContext ?: return
        val current = mutableState.value
        if (current.status != DispatchReadinessStatus.Current ||
            current.items.none { it.fulfillmentId == fulfillmentId }
        ) {
            return
        }
        val request = ++generation
        mutableState.value = current.copy(
            selectedFulfillmentId = fulfillmentId,
            detail = null,
            detailStatus = DispatchReadinessDetailStatus.Loading
        )
        viewModelScope.launch {
            val result = safeCall { gateway.detail(fulfillmentId, context) }
            if (!isCurrent(request, context)) return@launch
            when (result) {
                is DispatchReadinessGatewayResult.Detail -> {
                    val summary = current.items.firstOrNull { it.fulfillmentId == fulfillmentId }
                    if (summary == null || !result.item.matches(summary)) {
                        mutableState.value = mutableState.value.copy(
                            detail = null,
                            detailStatus = DispatchReadinessDetailStatus.ServiceUnavailable
                        )
                    } else {
                        mutableState.value = mutableState.value.copy(
                            detail = result.item,
                            detailStatus = DispatchReadinessDetailStatus.Current
                        )
                    }
                }

                DispatchReadinessGatewayResult.NetworkUnavailable -> failDetail(
                    DispatchReadinessDetailStatus.NetworkUnavailable
                )

                DispatchReadinessGatewayResult.ServiceUnavailable -> failDetail(
                    DispatchReadinessDetailStatus.ServiceUnavailable
                )

                DispatchReadinessGatewayResult.PermissionDenied -> {
                    activeContext = null
                    generation++
                    mutableState.value = DispatchReadinessUiState(
                        authorityEpoch = context.authorityEpoch,
                        status = DispatchReadinessStatus.PermissionDenied,
                        detailStatus = DispatchReadinessDetailStatus.PermissionDenied
                    )
                }

                DispatchReadinessGatewayResult.ContextInvalidated -> invalidateContext()

                DispatchReadinessGatewayResult.SessionInvalidated -> invalidateSession()

                is DispatchReadinessGatewayResult.ListResult -> failDetail(
                    DispatchReadinessDetailStatus.ServiceUnavailable
                )
            }
        }
    }

    fun clearSelection() {
        generation++
        mutableState.value = mutableState.value.copy(
            selectedFulfillmentId = null,
            detail = null,
            detailStatus = DispatchReadinessDetailStatus.NotRequested
        )
    }

    fun deactivate() {
        generation++
        activeContext = null
        mutableState.value = DispatchReadinessUiState()
    }

    fun invalidateContext() {
        generation++
        activeContext = null
        val epoch = mutableState.value.authorityEpoch
        mutableState.value = DispatchReadinessUiState(
            authorityEpoch = epoch,
            status = DispatchReadinessStatus.ContextInvalidated
        )
    }

    fun invalidateSession() {
        generation++
        activeContext = null
        val epoch = mutableState.value.authorityEpoch
        mutableState.value = DispatchReadinessUiState(
            authorityEpoch = epoch,
            status = DispatchReadinessStatus.SessionInvalidated
        )
    }

    private fun failList(context: DispatchAuthorityContext, status: DispatchReadinessStatus) {
        mutableState.value = DispatchReadinessUiState(
            authorityEpoch = context.authorityEpoch,
            status = status
        )
    }

    private fun failDetail(status: DispatchReadinessDetailStatus) {
        mutableState.value = mutableState.value.copy(detail = null, detailStatus = status)
    }

    private suspend fun safeCall(
        block: suspend () -> DispatchReadinessGatewayResult
    ): DispatchReadinessGatewayResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchReadinessGatewayResult.ServiceUnavailable
    }

    private fun isCurrent(request: Long, context: DispatchAuthorityContext): Boolean =
        request == generation && activeContext == context &&
            mutableState.value.authorityEpoch == context.authorityEpoch

    private fun DispatchAuthorityContext.readPermissionHint(): PermissionHint {
        val verified = identity ?: return PermissionHint.Unknown
        if (authorityEpoch <= 0 || listOf(
                verified.userId,
                verified.tenantId,
                verified.workspaceId,
                verified.membershipId
            ).any(String::isBlank)
        ) {
            return PermissionHint.Unknown
        }
        return when {
            verified.permissions.isEmpty() -> PermissionHint.Unknown
            verified.permissions.any { it in DISPATCH_READ_PERMISSIONS } -> PermissionHint.Available
            else -> PermissionHint.Unavailable
        }
    }

    private fun DispatchReadiness.matches(summary: DispatchReadiness): Boolean =
        subjectKind == PREPARED_FULFILLMENT && fulfillmentId == summary.fulfillmentId &&
            physicalAllocationId == summary.physicalAllocationId &&
            fulfillmentVersion >= summary.fulfillmentVersion &&
            physicalAllocationVersion >= summary.physicalAllocationVersion &&
            deliveryId == summary.deliveryId

    private enum class PermissionHint { Available, Unavailable, Unknown }

    private companion object {
        const val PREPARED_FULFILLMENT = "PREPARED_FULFILLMENT"
        val DISPATCH_READ_PERMISSIONS = setOf("dispatch.read")
        val INVALIDATED_STATUS = setOf(
            DispatchReadinessStatus.ContextInvalidated,
            DispatchReadinessStatus.SessionInvalidated
        )
    }
}
