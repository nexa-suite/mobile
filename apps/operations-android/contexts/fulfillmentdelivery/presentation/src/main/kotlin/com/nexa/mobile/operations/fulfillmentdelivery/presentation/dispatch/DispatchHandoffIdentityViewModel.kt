package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffIdentityCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffIdentityGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffIdentityMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffIssueResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffValidationResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchHandoffIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.isValidOpaqueIdentifier
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Issues and explicitly validates dispatch identity; it never records delivery or receipt outcomes. */
class DispatchHandoffIdentityViewModel(
    private val gateway: DispatchHandoffIdentityGateway,
    private val metadataStore: DispatchHandoffIdentityMetadataStore,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Instant = Instant::now,
    private val requestBodyCodec: DispatchRequestBodyCodec
) : ViewModel() {
    private val mutableState = MutableStateFlow(DispatchHandoffIdentityUiState())
    val state = mutableState.asStateFlow()

    private var authority: DispatchAuthorityContext? = null
    private var generation = 0L
    private var pendingCommand: DispatchHandoffIdentityCommand? = null
    private var activeJob: Job? = null
    private var expiryJob: Job? = null

    /** Restores only an exact-scope idempotency command; it never sends it automatically. */
    fun activate(context: DispatchAuthorityContext, deliveryId: String, assignmentId: String) {
        expiryJob?.cancel()
        expiryJob = null
        generation++
        val request = generation
        activeJob?.cancel()
        pendingCommand = null
        authority = context
        val identity = context.identity
        val canIssue = identity?.permissions?.contains(HANDOFF_WRITE_PERMISSION) == true
        val canValidate = identity?.permissions?.contains(HANDOFF_READ_PERMISSION) == true
        mutableState.value = DispatchHandoffIdentityUiState(
            authorityEpoch = context.authorityEpoch,
            deliveryId = deliveryId,
            assignmentId = assignmentId,
            status = DispatchHandoffIdentityStatus.Loading,
            canIssueByAuthority = canIssue,
            canValidateByAuthority = canValidate
        )
        if (!context.validIdentity() || !deliveryId.isUuid() || !assignmentId.isUuid() ||
            (!canIssue && !canValidate)
        ) {
            authority = null
            mutableState.update { it.copy(status = DispatchHandoffIdentityStatus.PermissionDenied) }
            return
        }
        activeJob = viewModelScope.launch {
            when (val stored = safeLoad(identity, deliveryId, assignmentId)) {
                is DispatchHandoffMetadataRead.Available -> {
                    if (!isCurrent(request, context)) return@launch
                    val command = stored.command
                    if (command != null && !requestBodyCodec.isValid(command)) {
                        mutableState.update {
                            it.copy(status = DispatchHandoffIdentityStatus.PersistenceUnavailable)
                        }
                        return@launch
                    }
                    pendingCommand = command
                    mutableState.update {
                        it.copy(
                            command = command,
                            status = if (command == null) {
                                DispatchHandoffIdentityStatus.Ready
                            } else {
                                DispatchHandoffIdentityStatus.UnknownOutcome
                            }
                        )
                    }
                }

                DispatchHandoffMetadataRead.Unavailable -> {
                    if (isCurrent(request, context)) {
                        mutableState.update {
                            it.copy(status = DispatchHandoffIdentityStatus.PersistenceUnavailable)
                        }
                    }
                }
            }
        }
    }

    /** Initial issue uses a fresh durable key and only runs after an explicit user action. */
    fun issue() {
        val context = authority ?: return
        val current = mutableState.value
        if (!current.canIssue || current.status != DispatchHandoffIdentityStatus.Ready) return
        issueWithNewKey(context, current, replacingKey = null)
    }

    /** Explicit same-key retry after an uncertain outcome; never creates a replacement key. */
    fun retrySame() {
        val context = authority ?: return
        val current = mutableState.value
        val command = pendingCommand ?: return
        if (!current.canRetrySame || !authorized(context, HANDOFF_WRITE_PERMISSION)) return
        val request = generation
        mutableState.update {
            it.copy(
                status = DispatchHandoffIdentityStatus.Issuing,
                busy = true,
                errorCode = null,
                oneTimeToken = null
            )
        }
        activeJob = viewModelScope.launch {
            sendIssue(context, request, command)
        }
    }

    /** Explicit fresh issuance after replay proved the old token is not recoverable or validation rejected. */
    fun issueReplacement() {
        val context = authority ?: return
        val current = mutableState.value
        if (!current.canIssue || current.status !in REISSUE_STATUSES) return
        issueWithNewKey(context, current, replacingKey = pendingCommand?.idempotencyKey)
    }

    fun tokenChanged(token: String) {
        if (token.length > MAX_DISPATCH_HANDOFF_TOKEN_LENGTH) {
            mutableState.update { it.copy(enteredToken = "", errorCode = "HANDOFF_TOKEN_TOO_LONG") }
        } else {
            mutableState.update { it.copy(enteredToken = token, errorCode = null) }
        }
    }

    /** Validation is an explicit read-only POST; input is erased regardless of result. */
    fun validate() {
        val context = authority ?: return
        val current = mutableState.value
        val deliveryId = current.deliveryId ?: return
        val assignmentId = current.assignmentId ?: return
        val token = current.enteredToken.takeIf { it.isNotBlank() } ?: return
        if (!current.canValidate || !authorized(context, HANDOFF_READ_PERMISSION)) return
        val request = ++generation
        mutableState.update {
            it.copy(
                status = DispatchHandoffIdentityStatus.Validating,
                busy = true,
                enteredToken = "",
                oneTimeToken = null,
                identity = null,
                errorCode = null
            )
        }
        activeJob = viewModelScope.launch {
            val result = safeValidate(deliveryId, assignmentId, token, context)
            if (!isCurrent(request, context)) return@launch
            when (result) {
                is DispatchHandoffValidationResult.Validated -> {
                    if (result.identity.matches(deliveryId, assignmentId) &&
                        isFuture(result.identity.expiresAt)
                    ) {
                        mutableState.update {
                            it.copy(
                                status = DispatchHandoffIdentityStatus.IdentityValidated,
                                busy = false,
                                identity = result.identity,
                                errorCode = null
                            )
                        }
                    } else {
                        mutableState.update {
                            it.copy(
                                status = DispatchHandoffIdentityStatus.ValidationUnknown,
                                busy = false,
                                identity = null,
                                errorCode = "HANDOFF_RESPONSE_INVALID"
                            )
                        }
                    }
                }

                is DispatchHandoffValidationResult.Rejected -> mutableState.update {
                    it.copy(
                        status = DispatchHandoffIdentityStatus.ValidationRejected,
                        busy = false,
                        identity = null,
                        errorCode = result.code
                    )
                }

                DispatchHandoffValidationResult.NotFound -> mutableState.update {
                    it.copy(
                        status = DispatchHandoffIdentityStatus.ValidationRejected,
                        busy = false,
                        identity = null,
                        errorCode = "HANDOFF_IDENTITY_NOT_FOUND"
                    )
                }

                DispatchHandoffValidationResult.Unavailable -> validationUnknown(
                    "HANDOFF_RESULT_UNKNOWN"
                )

                DispatchHandoffValidationResult.PermissionDenied -> fail(
                    DispatchHandoffIdentityStatus.PermissionDenied
                )

                DispatchHandoffValidationResult.ContextInvalidated -> fail(
                    DispatchHandoffIdentityStatus.ContextInvalidated
                )

                DispatchHandoffValidationResult.SessionInvalidated -> fail(
                    DispatchHandoffIdentityStatus.SessionInvalidated
                )
            }
        }
    }

    /** Clears both entered and issued secrets and fences any late completion. */
    fun hideToken() {
        expiryJob?.cancel()
        expiryJob = null
        val old = mutableState.value
        val hadActiveOperation = old.busy
        generation++
        activeJob?.cancel()
        activeJob = null
        mutableState.update {
            it.copy(
                oneTimeToken = null,
                enteredToken = "",
                busy = false,
                status = when {
                    pendingCommand != null -> DispatchHandoffIdentityStatus.UnknownOutcome

                    hadActiveOperation && old.status == DispatchHandoffIdentityStatus.Issuing ->
                        DispatchHandoffIdentityStatus.UnknownOutcome

                    hadActiveOperation && it.status == DispatchHandoffIdentityStatus.Validating ->
                        DispatchHandoffIdentityStatus.Ready

                    it.status == DispatchHandoffIdentityStatus.TokenVisible ->
                        DispatchHandoffIdentityStatus.ReissueRequired

                    else -> it.status
                },
                errorCode = if (pendingCommand != null &&
                    hadActiveOperation
                ) {
                    "HANDOFF_RESULT_UNKNOWN"
                } else {
                    null
                }
            )
        }
    }

    /** Route close or authority change removes transient secrets; durable issue intent remains encrypted. */
    fun deactivate() {
        expiryJob?.cancel()
        expiryJob = null
        generation++
        activeJob?.cancel()
        activeJob = null
        authority = null
        pendingCommand = null
        mutableState.value = DispatchHandoffIdentityUiState()
    }

    private fun issueWithNewKey(
        context: DispatchAuthorityContext,
        current: DispatchHandoffIdentityUiState,
        replacingKey: String?
    ) {
        if (!authorized(context, HANDOFF_WRITE_PERMISSION)) return
        val deliveryId = current.deliveryId ?: return
        val assignmentId = current.assignmentId ?: return
        val key = keyFactory().takeIf { it.isNotBlank() && it.length <= 160 }
        if (key == null || current.busy) {
            mutableState.update {
                it.copy(
                    status = DispatchHandoffIdentityStatus.Rejected,
                    errorCode = "IDEMPOTENCY_KEY_INVALID"
                )
            }
            return
        }
        val command = try {
            DispatchHandoffIdentityCommand(
                deliveryId = deliveryId,
                assignmentId = assignmentId,
                idempotencyKey = key,
                frozenBody = requestBodyCodec.dispatchHandoffIssueBody(assignmentId)
            )
        } catch (_: IllegalArgumentException) {
            mutableState.update {
                it.copy(
                    status = DispatchHandoffIdentityStatus.Rejected,
                    errorCode = "HANDOFF_COMMAND_INVALID"
                )
            }
            return
        }
        val request = generation
        mutableState.update {
            it.copy(
                status = DispatchHandoffIdentityStatus.Issuing,
                busy = true,
                oneTimeToken = null,
                identity = null,
                errorCode = null
            )
        }
        activeJob = viewModelScope.launch {
            when (safePersist(context.identity!!, command, replacingKey)) {
                DispatchHandoffMetadataWrite.Saved -> {
                    if (!isCurrent(request, context)) return@launch
                    pendingCommand = command
                    mutableState.update { it.copy(command = command) }
                    sendIssue(context, request, command)
                }

                DispatchHandoffMetadataWrite.Conflict,
                DispatchHandoffMetadataWrite.Stale,
                DispatchHandoffMetadataWrite.Unavailable -> {
                    if (isCurrent(request, context)) {
                        mutableState.update {
                            it.copy(
                                status = DispatchHandoffIdentityStatus.PersistenceUnavailable,
                                busy = false,
                                command = pendingCommand
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun sendIssue(
        context: DispatchAuthorityContext,
        request: Long,
        command: DispatchHandoffIdentityCommand
    ) {
        val result = safeIssue(command, context)
        if (!isCurrent(request, context)) return
        when (result) {
            is DispatchHandoffIssueResult.Issued -> {
                if (!result.identity.matches(command.deliveryId, command.assignmentId) ||
                    !validToken(result.token) || !isFuture(result.identity.expiresAt)
                ) {
                    unknownIssue(command, "HANDOFF_RESPONSE_INVALID")
                } else {
                    val cleared = safeClear(context.identity!!, command)
                    if (!isCurrent(request, context)) return
                    if (!cleared) {
                        pendingCommand = command
                        mutableState.update {
                            it.copy(
                                status = DispatchHandoffIdentityStatus.PersistenceUnavailable,
                                busy = false,
                                oneTimeToken = null,
                                command = command
                            )
                        }
                        return
                    }
                    pendingCommand = null
                    mutableState.update {
                        it.copy(
                            status = DispatchHandoffIdentityStatus.TokenVisible,
                            busy = false,
                            command = null,
                            identity = result.identity,
                            oneTimeToken = result.token,
                            enteredToken = "",
                            errorCode = null
                        )
                    }
                    expiryJob?.cancel()
                    expiryJob = viewModelScope.launch {
                        val remaining = java.time.Duration.between(
                            now(),
                            Instant.parse(result.identity.expiresAt)
                        ).toMillis().coerceAtLeast(0)
                        delay(remaining)
                        if (isCurrent(request, context) &&
                            mutableState.value.oneTimeToken == result.token
                        ) {
                            hideToken()
                        }
                    }
                }
            }

            is DispatchHandoffIssueResult.AcceptedWithoutToken -> {
                if (!result.identity.matches(command.deliveryId, command.assignmentId)) {
                    unknownIssue(command, "HANDOFF_RESPONSE_INVALID")
                } else {
                    val cleared = safeClear(context.identity!!, command)
                    pendingCommand = if (cleared) null else command
                    mutableState.update {
                        it.copy(
                            status = if (cleared) {
                                DispatchHandoffIdentityStatus.ReissueRequired
                            } else {
                                DispatchHandoffIdentityStatus.PersistenceUnavailable
                            },
                            busy = false,
                            command = pendingCommand,
                            identity = result.identity,
                            oneTimeToken = null,
                            errorCode = "HANDOFF_TOKEN_NOT_RECOVERABLE"
                        )
                    }
                }
            }

            is DispatchHandoffIssueResult.Rejected -> knownIssueFailure(
                context,
                command,
                DispatchHandoffIdentityStatus.Rejected,
                result.code
            )

            DispatchHandoffIssueResult.NotFound -> knownIssueFailure(
                context,
                command,
                DispatchHandoffIdentityStatus.NotFound,
                "HANDOFF_NOT_FOUND"
            )

            DispatchHandoffIssueResult.PermissionDenied -> fail(
                DispatchHandoffIdentityStatus.PermissionDenied
            )

            DispatchHandoffIssueResult.ContextInvalidated -> fail(
                DispatchHandoffIdentityStatus.ContextInvalidated
            )

            DispatchHandoffIssueResult.SessionInvalidated -> fail(
                DispatchHandoffIdentityStatus.SessionInvalidated
            )

            DispatchHandoffIssueResult.UnknownOutcome,
            DispatchHandoffIssueResult.Unavailable -> unknownIssue(
                command,
                "HANDOFF_RESULT_UNKNOWN"
            )
        }
    }

    private suspend fun knownIssueFailure(
        context: DispatchAuthorityContext,
        command: DispatchHandoffIdentityCommand,
        status: DispatchHandoffIdentityStatus,
        code: String?
    ) {
        val cleared = safeClear(context.identity!!, command)
        pendingCommand = if (cleared) null else command
        mutableState.update {
            it.copy(
                status = if (cleared) {
                    status
                } else {
                    DispatchHandoffIdentityStatus.PersistenceUnavailable
                },
                busy = false,
                command = pendingCommand,
                oneTimeToken = null,
                identity = null,
                errorCode = code
            )
        }
    }

    private fun unknownIssue(command: DispatchHandoffIdentityCommand, code: String) {
        pendingCommand = command
        mutableState.update {
            it.copy(
                status = DispatchHandoffIdentityStatus.UnknownOutcome,
                busy = false,
                command = command,
                oneTimeToken = null,
                identity = null,
                errorCode = code
            )
        }
    }

    private fun validationUnknown(code: String) = mutableState.update {
        it.copy(
            status = DispatchHandoffIdentityStatus.ValidationUnknown,
            busy = false,
            identity = null,
            oneTimeToken = null,
            enteredToken = "",
            errorCode = code
        )
    }

    private fun fail(status: DispatchHandoffIdentityStatus) = mutableState.update {
        it.copy(
            status = status,
            busy = false,
            oneTimeToken = null,
            enteredToken = "",
            identity = null
        )
    }

    private suspend fun safeIssue(
        command: DispatchHandoffIdentityCommand,
        context: DispatchAuthorityContext
    ): DispatchHandoffIssueResult = try {
        gateway.issue(command, context)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchHandoffIssueResult.UnknownOutcome
    }

    private suspend fun safeValidate(
        deliveryId: String,
        assignmentId: String,
        token: String,
        context: DispatchAuthorityContext
    ): DispatchHandoffValidationResult = try {
        gateway.validate(deliveryId, assignmentId, token, context)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchHandoffValidationResult.Unavailable
    }

    private suspend fun safeLoad(
        identity: DispatchAuthorityIdentity,
        deliveryId: String,
        assignmentId: String
    ): DispatchHandoffMetadataRead = try {
        metadataStore.load(identity, deliveryId, assignmentId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchHandoffMetadataRead.Unavailable
    }

    private suspend fun safePersist(
        identity: DispatchAuthorityIdentity,
        command: DispatchHandoffIdentityCommand,
        replacingKey: String?
    ): DispatchHandoffMetadataWrite = try {
        metadataStore.persistIntent(identity, command, replacingKey)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchHandoffMetadataWrite.Unavailable
    }

    private suspend fun safeClear(
        identity: DispatchAuthorityIdentity,
        command: DispatchHandoffIdentityCommand
    ): Boolean = try {
        metadataStore.clearCommand(
            identity,
            command.deliveryId,
            command.assignmentId,
            command.idempotencyKey
        ) == DispatchHandoffMetadataWrite.Saved
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun authorized(context: DispatchAuthorityContext, permission: String): Boolean =
        context == authority && context.validIdentity() &&
            context.identity?.permissions?.contains(permission) == true

    private fun isCurrent(request: Long, context: DispatchAuthorityContext): Boolean =
        request == generation && authority == context &&
            mutableState.value.authorityEpoch == context.authorityEpoch

    private fun isFuture(value: String): Boolean = try {
        Instant.parse(value).isAfter(now())
    } catch (_: Exception) {
        false
    }

    private fun validToken(token: String): Boolean =
        token.isNotBlank() && token.length <= MAX_DISPATCH_HANDOFF_TOKEN_LENGTH

    private fun DispatchHandoffIdentity.matches(deliveryId: String, assignmentId: String): Boolean =
        this.deliveryId.equals(deliveryId, ignoreCase = true) &&
            this.assignmentId.equals(assignmentId, ignoreCase = true) &&
            handoffId.isUuid() && deliveryVersion >= 0 && status == "ACTIVE"

    private fun DispatchAuthorityContext.validIdentity(): Boolean {
        val value = identity ?: return false
        return authorityEpoch > 0 && listOf(
            value.userId,
            value.tenantId,
            value.workspaceId,
            value.membershipId
        ).all(String::isNotBlank) && value.permissions.isNotEmpty()
    }

    private fun String.isUuid(): Boolean = isValidOpaqueIdentifier(this)

    private companion object {
        const val HANDOFF_WRITE_PERMISSION = "logistics:write"
        const val HANDOFF_READ_PERMISSION = "logistics:read"
        val REISSUE_STATUSES = setOf(
            DispatchHandoffIdentityStatus.ReissueRequired,
            DispatchHandoffIdentityStatus.ValidationRejected,
            DispatchHandoffIdentityStatus.ValidationUnknown
        )
    }
}
