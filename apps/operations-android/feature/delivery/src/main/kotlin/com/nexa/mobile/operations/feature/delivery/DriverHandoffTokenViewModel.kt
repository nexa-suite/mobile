package com.nexa.mobile.operations.feature.delivery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Driver-side issue/display only. Buyer validation and receipt flows are separate authorities. */
class DriverHandoffTokenViewModel(
    private val gateway: DriverHandoffTokenGateway,
    private val metadataStore: DriverHandoffTokenMetadataStore?,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(DriverHandoffTokenUiState())
    val state = mutableState.asStateFlow()

    private var authority: DriverDeliveryAuthority? = null
    private var generation = 0L
    private var command: DriverHandoffIssueCommand? = null
    private var expiryJob: Job? = null

    fun activate(
        currentAuthority: DriverDeliveryAuthority,
        deliveryId: String,
        attemptId: String,
        deliveryVersion: Long
    ) {
        generation++
        val requestGeneration = generation
        expiryJob?.cancel()
        command = null
        authority = currentAuthority
        mutableState.value = DriverHandoffTokenUiState(
            authorityEpoch = currentAuthority.authorityEpoch,
            deliveryId = deliveryId,
            attemptId = attemptId,
            status = DriverHandoffUiStatus.Loading
        )
        if (!canUse(currentAuthority) || deliveryId.isBlank() || attemptId.isBlank() || deliveryVersion < 0) {
            mutableState.update { it.copy(status = DriverHandoffUiStatus.PermissionDenied) }
            return
        }
        viewModelScope.launch {
            when (val stored = safeLoad(currentAuthority.scopeIdentity, deliveryId, attemptId)) {
                is DriverHandoffMetadataRead.Available -> {
                    command = stored.command
                    if (stored.command != null) {
                        mutableState.update {
                            it.copy(command = stored.command, status = DriverHandoffUiStatus.UnknownOutcome)
                        }
                    }
                }
                DriverHandoffMetadataRead.Unavailable -> {
                    mutableState.update { it.copy(status = DriverHandoffUiStatus.PersistenceUnavailable) }
                    return@launch
                }
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            refreshCurrent(requestGeneration, currentAuthority, deliveryId, attemptId, deliveryVersion)
        }
    }

    /** Explicit fresh read. It never issues or replays a handoff token. */
    fun refresh() {
        val currentAuthority = authority ?: return
        val state = mutableState.value
        val deliveryId = state.deliveryId ?: return
        val attemptId = state.attemptId ?: return
        if (state.busy || command != null) return
        val requestGeneration = generation
        mutableState.update { it.copy(status = DriverHandoffUiStatus.Loading, errorCode = null) }
        viewModelScope.launch {
            refreshCurrent(requestGeneration, currentAuthority, deliveryId, attemptId,
                state.delivery?.version ?: 0L)
        }
    }

    /** Creates one durable command, or explicitly replays that exact command after uncertainty. */
    fun issueOrRetrySame() {
        val currentAuthority = authority ?: return
        if (!canUse(currentAuthority)) {
            mutableState.update { it.copy(status = DriverHandoffUiStatus.PermissionDenied, token = null) }
            return
        }
        val current = mutableState.value
        val deliveryId = current.deliveryId ?: return
        val attemptId = current.attemptId ?: return
        if (current.busy || current.status == DriverHandoffUiStatus.TokenVisible) return
        if (current.command == null && !current.canIssue) return
        if (current.command != null && !current.canRetrySame) return
        val requestGeneration = generation
        mutableState.update { it.copy(busy = true, token = null, errorCode = null, status = DriverHandoffUiStatus.Issuing) }
        viewModelScope.launch {
            val freshResult = safeCurrent(deliveryId, currentAuthority)
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            val fresh = (freshResult as? DriverHandoffCurrentDeliveryResult.Loaded)?.delivery
            if (fresh == null) {
                mutableState.update { it.copy(busy = false, status = freshResult.toStatus(), delivery = null) }
                return@launch
            }
            if (!fresh.matches(deliveryId, attemptId)) {
                mutableState.update { it.copy(busy = false, delivery = fresh, status = DriverHandoffUiStatus.Stale) }
                return@launch
            }
            val frozen = command ?: run {
                val key = keyFactory().takeIf { it.isNotBlank() && it.length <= 160 }
                if (key == null) {
                    mutableState.update { it.copy(busy = false, status = DriverHandoffUiStatus.Rejected,
                        errorCode = "IDEMPOTENCY_KEY_INVALID") }
                    return@launch
                }
                DriverHandoffIssueCommand(
                    deliveryId, attemptId, fresh.version, key, driverHandoffIssueBody(attemptId)
                )
            }
            if (command == null) {
                val persisted = safePersist(currentAuthority.scopeIdentity, frozen)
                if (persisted != DriverHandoffMetadataWrite.Saved) {
                    mutableState.update { it.copy(busy = false, status = DriverHandoffUiStatus.PersistenceUnavailable,
                        command = null) }
                    return@launch
                }
                command = frozen
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            mutableState.update { it.copy(command = frozen, delivery = fresh) }
            val result = try {
                gateway.issue(frozen, currentAuthority)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DriverHandoffIssueResult.UnknownOutcome
            }
            if (!isCurrent(requestGeneration, currentAuthority)) return@launch
            when (result) {
                is DriverHandoffIssueResult.Issued -> {
                    if (!result.matches(frozen) || !validToken(result.token) ||
                        !isFuture(result.receipt.expiresAt)
                    ) {
                        mutableState.update {
                            it.copy(busy = false, token = null, status = DriverHandoffUiStatus.UnknownOutcome,
                                errorCode = "HANDOFF_RESPONSE_INVALID")
                        }
                    } else {
                        mutableState.update {
                            it.copy(busy = false, receipt = result.receipt, token = result.token,
                                status = DriverHandoffUiStatus.TokenVisible, errorCode = null)
                        }
                        scheduleExpiry(requestGeneration, result.receipt.expiresAt)
                    }
                }
                is DriverHandoffIssueResult.TokenUnavailable -> {
                    if (!result.receipt.matches(frozen)) {
                        mutableState.update {
                            it.copy(busy = false, status = DriverHandoffUiStatus.UnknownOutcome,
                                errorCode = "HANDOFF_RESPONSE_INVALID")
                        }
                    } else {
                        mutableState.update {
                            it.copy(busy = false, receipt = result.receipt, token = null,
                                status = DriverHandoffUiStatus.TokenUnavailable,
                                errorCode = "HANDOFF_TOKEN_NOT_RECOVERABLE")
                        }
                    }
                }
                is DriverHandoffIssueResult.Rejected -> {
                    val cleared = safeClearKnownRejection(
                        currentAuthority.scopeIdentity, frozen
                    ) == DriverHandoffMetadataWrite.Saved
                    if (cleared) command = null
                    mutableState.update {
                        it.copy(busy = false, token = null, command = if (cleared) null else frozen,
                            status = if (cleared) DriverHandoffUiStatus.Rejected else DriverHandoffUiStatus.PersistenceUnavailable,
                            errorCode = result.code)
                    }
                }
                DriverHandoffIssueResult.NotFound -> {
                    val cleared = safeClearKnownRejection(currentAuthority.scopeIdentity, frozen) ==
                        DriverHandoffMetadataWrite.Saved
                    if (cleared) command = null
                    mutableState.update {
                        it.copy(busy = false, token = null, command = if (cleared) null else frozen,
                            status = if (cleared) DriverHandoffUiStatus.NotFound else DriverHandoffUiStatus.PersistenceUnavailable)
                    }
                }
                DriverHandoffIssueResult.PermissionDenied -> {
                    mutableState.update { it.copy(busy = false, token = null, status = DriverHandoffUiStatus.PermissionDenied) }
                }
                DriverHandoffIssueResult.ContextInvalidated,
                DriverHandoffIssueResult.SessionInvalidated,
                DriverHandoffIssueResult.UnknownOutcome,
                DriverHandoffIssueResult.Unavailable -> {
                    mutableState.update { it.copy(busy = false, token = null, command = frozen,
                        status = DriverHandoffUiStatus.UnknownOutcome, errorCode = "HANDOFF_RESULT_UNKNOWN") }
                }
            }
        }
    }

    /** Called when the app backgrounds, route/context changes, or the user hides the token. */
    fun clearToken() {
        expiryJob?.cancel()
        expiryJob = null
        val state = mutableState.value
        if (state.busy) {
            generation++
            mutableState.update {
                it.copy(token = null, busy = false,
                    status = if (it.command != null) DriverHandoffUiStatus.UnknownOutcome
                    else if (it.delivery != null) DriverHandoffUiStatus.Ready else DriverHandoffUiStatus.Loading,
                    errorCode = if (it.command != null) "HANDOFF_RESULT_UNKNOWN" else null)
            }
            return
        }
        if (state.token == null) return
        generation++
        mutableState.update {
            it.copy(token = null, status = DriverHandoffUiStatus.Cleared,
                errorCode = "HANDOFF_TOKEN_CLEARED")
        }
    }

    fun invalidate() {
        generation++
        expiryJob?.cancel()
        expiryJob = null
        authority = null
        command = null
        mutableState.value = DriverHandoffTokenUiState()
    }

    private suspend fun refreshCurrent(
        requestGeneration: Long,
        currentAuthority: DriverDeliveryAuthority,
        deliveryId: String,
        attemptId: String,
        selectedVersion: Long
    ) {
        val result = safeCurrent(deliveryId, currentAuthority)
        if (!isCurrent(requestGeneration, currentAuthority)) return
        val fresh = (result as? DriverHandoffCurrentDeliveryResult.Loaded)?.delivery
        if (fresh == null) {
            mutableState.update { it.copy(status = result.toStatus(), delivery = null, busy = false) }
            return
        }
        if (!fresh.matches(deliveryId, attemptId)) {
            mutableState.update { it.copy(status = DriverHandoffUiStatus.Stale, delivery = fresh, busy = false) }
            return
        }
        if (fresh.version < selectedVersion) {
            mutableState.update { it.copy(status = DriverHandoffUiStatus.Stale, delivery = fresh, busy = false) }
            return
        }
        mutableState.update {
            it.copy(delivery = fresh, status = if (command == null) DriverHandoffUiStatus.Ready
                else DriverHandoffUiStatus.UnknownOutcome, busy = false)
        }
    }

    private fun scheduleExpiry(requestGeneration: Long, expiresAt: String) {
        expiryJob?.cancel()
        val expiry = try { Instant.parse(expiresAt) } catch (_: Exception) { return }
        val remaining = Duration.between(Instant.now(), expiry).toMillis().coerceAtLeast(0)
        expiryJob = viewModelScope.launch {
            delay(remaining)
            if (generation == requestGeneration) {
                mutableState.update {
                    if (it.token == null) it else it.copy(token = null, status = DriverHandoffUiStatus.TokenExpired,
                        errorCode = "HANDOFF_TOKEN_EXPIRED")
                }
            }
        }
    }

    private fun canUse(authority: DriverDeliveryAuthority): Boolean =
        authority.canRead && authority.permissions.contains("logistics:write")

    private fun DriverHandoffCurrentDelivery.matches(deliveryId: String, attemptId: String): Boolean =
        this.deliveryId == deliveryId && activeAttemptId == attemptId &&
            status in ACTIVE_DELIVERY_STATUSES && version >= 0

    private fun DriverHandoffIssueResult.Issued.matches(command: DriverHandoffIssueCommand): Boolean =
        receipt.matches(command)

    private fun DriverHandoffTokenReceipt.matches(command: DriverHandoffIssueCommand): Boolean =
        deliveryId == command.deliveryId && attemptId == command.attemptId &&
            handoffId.isNotBlank() && status == "ACTIVE"

    private fun validToken(token: String): Boolean = token.isNotBlank() && token.length <= 400

    private fun isFuture(value: String): Boolean =
        try { Instant.parse(value).isAfter(Instant.now()) } catch (_: Exception) { false }

    private suspend fun safeCurrent(
        deliveryId: String,
        currentAuthority: DriverDeliveryAuthority
    ): DriverHandoffCurrentDeliveryResult = try {
        gateway.currentDelivery(deliveryId, currentAuthority)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverHandoffCurrentDeliveryResult.Unavailable
    }

    private suspend fun safeLoad(
        scope: DriverAttemptScopeIdentity,
        deliveryId: String,
        attemptId: String
    ): DriverHandoffMetadataRead = try {
        metadataStore?.load(scope, deliveryId, attemptId) ?: DriverHandoffMetadataRead.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverHandoffMetadataRead.Unavailable
    }

    private suspend fun safePersist(
        scope: DriverAttemptScopeIdentity,
        command: DriverHandoffIssueCommand
    ): DriverHandoffMetadataWrite = try {
        metadataStore?.persistIntent(scope, command) ?: DriverHandoffMetadataWrite.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverHandoffMetadataWrite.Unavailable
    }

    private suspend fun safeClearKnownRejection(
        scope: DriverAttemptScopeIdentity,
        command: DriverHandoffIssueCommand
    ): DriverHandoffMetadataWrite = try {
        metadataStore?.clearKnownRejection(scope, command.deliveryId, command.attemptId,
            command.idempotencyKey) ?: DriverHandoffMetadataWrite.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DriverHandoffMetadataWrite.Unavailable
    }

    private fun DriverHandoffCurrentDeliveryResult.toStatus(): DriverHandoffUiStatus = when (this) {
        DriverHandoffCurrentDeliveryResult.NotFound -> DriverHandoffUiStatus.NotFound
        DriverHandoffCurrentDeliveryResult.PermissionDenied -> DriverHandoffUiStatus.PermissionDenied
        DriverHandoffCurrentDeliveryResult.ContextInvalidated,
        DriverHandoffCurrentDeliveryResult.SessionInvalidated,
        DriverHandoffCurrentDeliveryResult.Unavailable -> DriverHandoffUiStatus.Unavailable
        is DriverHandoffCurrentDeliveryResult.Loaded -> DriverHandoffUiStatus.Ready
    }

    private fun isCurrent(requestGeneration: Long, currentAuthority: DriverDeliveryAuthority): Boolean =
        generation == requestGeneration && authority == currentAuthority

    private companion object {
        val ACTIVE_DELIVERY_STATUSES = setOf("ASSIGNED", "DISPATCHED", "IN_TRANSIT", "PARTIAL")
    }
}
