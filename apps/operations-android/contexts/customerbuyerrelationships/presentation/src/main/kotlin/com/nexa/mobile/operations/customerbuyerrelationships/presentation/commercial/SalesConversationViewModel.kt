package com.nexa.mobile.operations.customerbuyerrelationships.presentation.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesConversationGateway
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesConversationProjection
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesConversationRead
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesMessageSendResult
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesMessagingAuthority
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesPendingMessage
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesPendingMessageRead
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SalesConversationStatus {
    Idle,
    Loading,
    Current,
    Sending,
    Unavailable,
    PermissionDenied,
    ContextInvalidated
}

data class SalesConversationState(
    val accountId: String? = null,
    val projection: SalesConversationProjection? = null,
    val pending: SalesPendingMessage? = null,
    val pendingStoreAvailable: Boolean = false,
    val status: SalesConversationStatus = SalesConversationStatus.Idle,
    val confirmedSendRevision: Long = 0
)

class SalesConversationViewModel(private val gateway: SalesConversationGateway) : ViewModel() {
    private val mutableState = MutableStateFlow(SalesConversationState())
    val state = mutableState.asStateFlow()
    private var authority: SalesMessagingAuthority? = null
    private var generation = 0L

    fun activate(value: SalesMessagingAuthority, accountId: String) {
        val captured = value.copy(permissions = value.permissions.toSet())
        if (authority == captured && state.value.accountId == accountId) {
            refresh()
            return
        }
        generation++
        authority = captured
        mutableState.value = SalesConversationState(
            accountId = accountId,
            status = SalesConversationStatus.Loading
        )
        load(captured, accountId, ++generation)
    }

    fun refresh() {
        if (state.value.status == SalesConversationStatus.Sending) return
        val captured = authority ?: return
        val accountId = state.value.accountId ?: return
        load(captured, accountId, ++generation)
    }

    fun send(recipientMembershipId: String, body: String) {
        val captured = authority ?: return
        val current = state.value
        val projection = current.projection ?: return
        if (!captured.canManageMessages || current.status == SalesConversationStatus.Sending ||
            current.pending != null || current.accountId == null || body.isBlank() ||
            body.length > MAX_MESSAGE_LENGTH ||
            projection.buyers.none { it.membershipId == recipientMembershipId }
        ) {
            return
        }
        send(
            captured,
            SalesPendingMessage(
                accountId = current.accountId,
                recipientMembershipId = recipientMembershipId,
                body = body,
                idempotencyKey = UUID.randomUUID().toString()
            ),
            isRetry = false
        )
    }

    fun retryPending() {
        val captured = authority ?: return
        val current = state.value
        val pending = current.pending ?: return
        if (current.status == SalesConversationStatus.Sending ||
            !captured.canManageMessages || pending.accountId != current.accountId ||
            !current.pendingStoreAvailable
        ) {
            return
        }
        send(captured, pending, isRetry = true)
    }

    private fun send(
        captured: SalesMessagingAuthority,
        message: SalesPendingMessage,
        isRetry: Boolean
    ) {
        val request = ++generation
        mutableState.value = state.value.copy(status = SalesConversationStatus.Sending)
        viewModelScope.launch {
            val result = try {
                gateway.send(captured, message, isRetry)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SalesMessageSendResult.Unavailable
            }
            if (request != generation || authority != captured) return@launch
            when (result) {
                SalesMessageSendResult.Confirmed -> {
                    val confirmedRevision = state.value.confirmedSendRevision + 1
                    mutableState.value = state.value.copy(
                        pending = null,
                        status = SalesConversationStatus.Loading,
                        confirmedSendRevision = confirmedRevision
                    )
                    load(captured, message.accountId, request)
                }

                SalesMessageSendResult.Unavailable -> {
                    mutableState.value = state.value.copy(
                        pending = message,
                        pendingStoreAvailable = true,
                        status = SalesConversationStatus.Unavailable
                    )
                }

                SalesMessageSendResult.PermissionDenied -> {
                    mutableState.value = SalesConversationState(
                        status = SalesConversationStatus.PermissionDenied
                    )
                }

                SalesMessageSendResult.ContextInvalidated -> {
                    mutableState.value = SalesConversationState(
                        status = SalesConversationStatus.ContextInvalidated
                    )
                }
            }
        }
    }

    private fun load(captured: SalesMessagingAuthority, accountId: String, request: Long) {
        mutableState.value = state.value.copy(
            accountId = accountId,
            projection = null,
            status = SalesConversationStatus.Loading
        )
        viewModelScope.launch {
            val pendingResult = try {
                gateway.pending(captured)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SalesPendingMessageRead.Unavailable
            }
            val conversationResult = try {
                gateway.load(captured, accountId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SalesConversationRead.Unavailable
            }
            if (request != generation || authority != captured) return@launch
            if (pendingResult is SalesPendingMessageRead.ContextInvalidated ||
                conversationResult == SalesConversationRead.ContextInvalidated
            ) {
                mutableState.value = SalesConversationState(
                    status = SalesConversationStatus.ContextInvalidated
                )
                return@launch
            }
            if (pendingResult is SalesPendingMessageRead.PermissionDenied ||
                conversationResult == SalesConversationRead.PermissionDenied
            ) {
                mutableState.value = SalesConversationState(
                    status = SalesConversationStatus.PermissionDenied
                )
                return@launch
            }
            val projection = (conversationResult as? SalesConversationRead.Current)?.value
            val pending = (pendingResult as? SalesPendingMessageRead.Current)?.value
            val pendingStoreAvailable = pendingResult is SalesPendingMessageRead.Current
            val status = if (projection != null && pendingStoreAvailable) {
                SalesConversationStatus.Current
            } else {
                SalesConversationStatus.Unavailable
            }
            mutableState.value = SalesConversationState(
                accountId = accountId,
                projection = projection,
                pending = pending,
                pendingStoreAvailable = pendingStoreAvailable,
                status = status,
                confirmedSendRevision = state.value.confirmedSendRevision
            )
        }
    }

    fun deactivate() {
        generation++
        authority = null
        mutableState.value = SalesConversationState()
    }

    private companion object {
        const val MAX_MESSAGE_LENGTH = 4000
    }
}
