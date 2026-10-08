package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchDeliveryInstructionsStatus as DeliveryInstructionsStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionsGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDeliveryInstructionKind
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDeliveryInstructionReceipt
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionScopeIdentity as InstructionScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionsGatewayResult as InstructionsGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDeliveryInstructionsSnapshot
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DispatchDeliveryInstructionsViewModel(
    private val gateway: DispatchDeliveryInstructionsGateway,
    private val metadata: DispatchDeliveryInstructionMetadataStore,
    private val commandKey: () -> String = { UUID.randomUUID().toString() },
    private val requestBodyCodec: DispatchRequestBodyCodec
) : ViewModel() {
    private val mutableState = MutableStateFlow(DispatchDeliveryInstructionsUiState())
    val state = mutableState.asStateFlow()

    private var activeContext: DispatchAuthorityContext? = null
    private var activeDeliveryId: String? = null
    private var pendingIntent: DispatchDeliveryInstructionIntent? = null
    private var generation = 0L

    fun activate(context: DispatchAuthorityContext, deliveryId: String? = null) {
        if (activeContext == context && activeDeliveryId == deliveryId &&
            mutableState.value.status !in INVALIDATED_STATUSES
        ) {
            return
        }
        activeContext = context
        activeDeliveryId = null
        pendingIntent = null
        generation++
        mutableState.value = baseState(context).copy(deliveryIdInput = deliveryId.orEmpty())
        if (deliveryId != null) loadDelivery()
    }

    fun updateDeliveryId(value: String) {
        val current = mutableState.value
        if (current.status == DeliveryInstructionsStatus.Saving) return
        activeDeliveryId = null
        pendingIntent = null
        generation++
        mutableState.value = baseState(activeContext ?: return).copy(deliveryIdInput = value)
    }

    fun loadDelivery() {
        val context = activeContext ?: return
        val deliveryId = mutableState.value.deliveryIdInput.trim()
        if (!UUID_PATTERN.matches(deliveryId)) {
            activeDeliveryId = null
            pendingIntent = null
            generation++
            mutableState.value = baseState(context).copy(
                deliveryIdInput = mutableState.value.deliveryIdInput,
                status = DeliveryInstructionsStatus.InvalidDeliveryId
            )
            return
        }
        val scope = context.scopeIdentity()
        if (scope == null) {
            fail(DeliveryInstructionsStatus.ContextInvalidated)
            return
        }
        activeDeliveryId = deliveryId
        pendingIntent = null
        val request = ++generation
        mutableState.value = baseState(context).copy(
            deliveryIdInput = deliveryId,
            status = DeliveryInstructionsStatus.Loading
        )
        viewModelScope.launch {
            val restored = safeMetadataRead { metadata.loadIntent(scope, deliveryId) }
            if (!isCurrent(request, context, deliveryId)) return@launch
            var currentIntent: DispatchDeliveryInstructionIntent? = when (restored) {
                DispatchDeliveryInstructionMetadataRead.Unavailable -> {
                    failIfCurrent(
                        request,
                        context,
                        deliveryId,
                        DeliveryInstructionsStatus.ServiceUnavailable
                    )
                    return@launch
                }

                is DispatchDeliveryInstructionMetadataRead.Available -> restored.intent
            }
            if (currentIntent != null) {
                if (currentIntent.scope != scope || currentIntent.deliveryId != deliveryId ||
                    !requestBodyCodec.isValid(currentIntent)
                ) {
                    failIfCurrent(
                        request,
                        context,
                        deliveryId,
                        DeliveryInstructionsStatus.ServiceUnavailable
                    )
                    return@launch
                }
                if (currentIntent.status == DispatchDeliveryInstructionIntentStatus.Pending) {
                    val recovered = currentIntent.copy(
                        status = DispatchDeliveryInstructionIntentStatus.UnknownOutcome
                    )
                    if (safeMetadataWrite { metadata.saveIntent(recovered) } !=
                        DispatchDeliveryInstructionMetadataWrite.Saved
                    ) {
                        failIfCurrent(
                            request,
                            context,
                            deliveryId,
                            DeliveryInstructionsStatus.ServiceUnavailable
                        )
                        return@launch
                    }
                    currentIntent = recovered
                }
            }
            when (
                val result = safeGatewayRead {
                    gateway.currentInstructions(deliveryId, context)
                }
            ) {
                is InstructionsGatewayResult.Snapshot -> {
                    if (!isCurrent(request, context, deliveryId)) return@launch
                    val snapshot = result.value
                    if (!snapshot.isValidFor(deliveryId)) {
                        failIfCurrent(
                            request,
                            context,
                            deliveryId,
                            DeliveryInstructionsStatus.ServiceUnavailable
                        )
                        return@launch
                    }
                    pendingIntent = currentIntent
                    mutableState.value = baseState(context).copy(
                        deliveryIdInput = deliveryId,
                        snapshot = snapshot,
                        pendingIntent = currentIntent,
                        metadataReady = true,
                        status = if (currentIntent ==
                            null
                        ) {
                            DeliveryInstructionsStatus.Current
                        } else {
                            DeliveryInstructionsStatus.UnknownOutcome
                        }
                    )
                }

                else -> if (isCurrent(request, context, deliveryId)) fail(result.toStatus())
            }
        }
    }

    fun refresh() = loadDelivery()

    fun startNewInstruction() {
        val current = mutableState.value
        if (current.status != DeliveryInstructionsStatus.Current || !current.canPublish ||
            current.pendingIntent != null
        ) {
            return
        }
        mutableState.value = current.copy(
            selectedInstructionId = null,
            selectedKind = DispatchDeliveryInstructionKind.NORMAL,
            content = "",
            errorCode = null
        )
    }

    fun editOperationalInstruction(instructionId: String) {
        val current = mutableState.value
        val instruction = current.snapshot?.instructions?.firstOrNull {
            it.id.equals(instructionId, ignoreCase = true)
        } ?: return
        if (current.status != DeliveryInstructionsStatus.Current || !current.canPublish ||
            instruction.sourceKind !=
            DispatchDeliveryInstructionsUiState.OPERATIONAL_DISPATCH_SOURCE ||
            current.pendingIntent != null
        ) {
            return
        }
        mutableState.value = current.copy(
            selectedInstructionId = instruction.id,
            selectedKind = instruction.kind,
            content = instruction.content,
            errorCode = null
        )
    }

    fun updateKind(value: DispatchDeliveryInstructionKind) {
        val current = mutableState.value
        if (current.status != DeliveryInstructionsStatus.Current ||
            current.pendingIntent != null
        ) {
            return
        }
        mutableState.value = current.copy(selectedKind = value)
    }

    fun updateContent(value: String) {
        val current = mutableState.value
        if (current.status != DeliveryInstructionsStatus.Current ||
            current.pendingIntent != null
        ) {
            return
        }
        mutableState.value =
            current.copy(
                content = value.take(DispatchDeliveryInstructionsUiState.MAX_INSTRUCTION_CONTENT)
            )
    }

    fun publish() {
        val current = mutableState.value
        val context = activeContext ?: return
        val snapshot = current.snapshot ?: return
        val deliveryId = activeDeliveryId ?: return
        if (!current.canPublishCurrent || snapshot.deliveryId != deliveryId) return
        val selected = current.selectedInstructionId?.let { id ->
            snapshot.instructions.firstOrNull { it.id.equals(id, ignoreCase = true) }
        }
        if (current.selectedInstructionId != null &&
            selected?.sourceKind != DispatchDeliveryInstructionsUiState.OPERATIONAL_DISPATCH_SOURCE
        ) {
            fail(DeliveryInstructionsStatus.StaleVersion)
            return
        }
        val scope = context.scopeIdentity() ?: run {
            fail(DeliveryInstructionsStatus.ContextInvalidated)
            return
        }
        val content = current.content.trim()
        val intent = DispatchDeliveryInstructionIntent(
            scope = scope,
            deliveryId = deliveryId,
            expectedDeliveryVersion = snapshot.deliveryVersion,
            instructionId = selected?.id,
            kind = current.selectedKind,
            content = content,
            exactRequestBody = requestBodyCodec.dispatchDeliveryInstructionRequestBody(
                selected?.id,
                current.selectedKind,
                content
            ),
            idempotencyKey = commandKey()
        )
        val request = ++generation
        mutableState.value =
            current.copy(status = DeliveryInstructionsStatus.Saving, pendingIntent = intent)
        viewModelScope.launch {
            when (safeMetadataWrite { metadata.saveIntent(intent) }) {
                DispatchDeliveryInstructionMetadataWrite.Saved -> pendingIntent = intent

                DispatchDeliveryInstructionMetadataWrite.Conflict -> {
                    failIfCurrent(
                        request,
                        context,
                        deliveryId,
                        DeliveryInstructionsStatus.Conflict
                    )
                    return@launch
                }

                DispatchDeliveryInstructionMetadataWrite.Stale -> {
                    failIfCurrent(
                        request,
                        context,
                        deliveryId,
                        DeliveryInstructionsStatus.StaleVersion
                    )
                    return@launch
                }

                DispatchDeliveryInstructionMetadataWrite.Unavailable -> {
                    failIfCurrent(
                        request,
                        context,
                        deliveryId,
                        DeliveryInstructionsStatus.ServiceUnavailable
                    )
                    return@launch
                }
            }
            if (!isCurrent(request, context, deliveryId)) return@launch
            send(intent, context, request)
        }
    }

    fun retryUnknownOutcome() {
        val intent = pendingIntent ?: return
        val context = activeContext ?: return
        val current = mutableState.value
        if (!current.canRetryUnknownOutcome || intent.scope != context.scopeIdentity() ||
            intent.deliveryId != activeDeliveryId
        ) {
            return
        }
        val request = ++generation
        mutableState.value = current.copy(status = DeliveryInstructionsStatus.Saving)
        viewModelScope.launch { send(intent, context, request) }
    }

    fun deactivate() {
        generation++
        activeContext = null
        activeDeliveryId = null
        pendingIntent = null
        mutableState.value = DispatchDeliveryInstructionsUiState()
    }

    private suspend fun send(
        intent: DispatchDeliveryInstructionIntent,
        context: DispatchAuthorityContext,
        request: Long
    ) {
        val result = safeGatewayPublish { gateway.publish(intent, context) }
        if (!isCurrent(request, context, intent.deliveryId)) return
        when (result) {
            is InstructionsGatewayResult.Published -> {
                if (!result.value.isFor(intent)) {
                    markUnknown(intent, context, request)
                    return
                }
                if (safeMetadataWrite {
                        metadata.clearIntent(intent.scope, intent.deliveryId, intent.idempotencyKey)
                    } != DispatchDeliveryInstructionMetadataWrite.Saved
                ) {
                    markUnknown(intent, context, request)
                    return
                }
                pendingIntent = null
                loadDelivery()
            }

            InstructionsGatewayResult.UnknownOutcome -> markUnknown(
                intent,
                context,
                request
            )

            else -> {
                val status = result.toStatus()
                val cleared = safeMetadataWrite {
                    metadata.clearIntent(intent.scope, intent.deliveryId, intent.idempotencyKey)
                } == DispatchDeliveryInstructionMetadataWrite.Saved
                if (cleared) pendingIntent = null
                if (isCurrent(request, context, intent.deliveryId)) {
                    mutableState.value = mutableState.value.copy(
                        status = if (cleared) {
                            status
                        } else {
                            DeliveryInstructionsStatus.ServiceUnavailable
                        },
                        pendingIntent = pendingIntent,
                        errorCode = (result as? InstructionsGatewayResult.Rejected)?.code
                    )
                }
            }
        }
    }

    private suspend fun markUnknown(
        intent: DispatchDeliveryInstructionIntent,
        context: DispatchAuthorityContext,
        request: Long
    ) {
        val unknown = intent.copy(status = DispatchDeliveryInstructionIntentStatus.UnknownOutcome)
        val stored = safeMetadataWrite { metadata.saveIntent(unknown) } ==
            DispatchDeliveryInstructionMetadataWrite.Saved
        if (!isCurrent(request, context, intent.deliveryId)) return
        pendingIntent = unknown
        mutableState.value = mutableState.value.copy(
            status = DeliveryInstructionsStatus.UnknownOutcome,
            pendingIntent = unknown,
            metadataReady = stored,
            errorCode = null
        )
    }

    private fun baseState(context: DispatchAuthorityContext) = DispatchDeliveryInstructionsUiState(
        authorityEpoch = context.authorityEpoch,
        status = DeliveryInstructionsStatus.Initial,
        canRead = context.identity?.permissions?.contains(DISPATCH_READ) == true,
        canPublish = context.identity?.permissions?.contains(DISPATCH_SCHEDULE) == true
    )

    private fun fail(status: DeliveryInstructionsStatus) {
        mutableState.value = mutableState.value.copy(status = status)
    }

    private fun failIfCurrent(
        request: Long,
        context: DispatchAuthorityContext,
        deliveryId: String,
        status: DeliveryInstructionsStatus
    ) {
        if (isCurrent(request, context, deliveryId)) fail(status)
    }

    private fun isCurrent(
        request: Long,
        context: DispatchAuthorityContext,
        deliveryId: String
    ): Boolean = request == generation && activeContext == context && activeDeliveryId == deliveryId

    private suspend fun safeMetadataRead(
        call: suspend () -> DispatchDeliveryInstructionMetadataRead
    ): DispatchDeliveryInstructionMetadataRead = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchDeliveryInstructionMetadataRead.Unavailable
    }

    private suspend fun safeMetadataWrite(
        call: suspend () -> DispatchDeliveryInstructionMetadataWrite
    ): DispatchDeliveryInstructionMetadataWrite = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DispatchDeliveryInstructionMetadataWrite.Unavailable
    }

    private suspend fun safeGatewayRead(
        call: suspend () -> InstructionsGatewayResult
    ): InstructionsGatewayResult = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InstructionsGatewayResult.ServiceUnavailable
    }

    private suspend fun safeGatewayPublish(
        call: suspend () -> InstructionsGatewayResult
    ): InstructionsGatewayResult = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InstructionsGatewayResult.UnknownOutcome
    }

    private fun DispatchAuthorityContext.scopeIdentity(): InstructionScopeIdentity? =
        identity?.let {
            InstructionScopeIdentity(
                it.userId,
                it.tenantId,
                it.workspaceId,
                it.membershipId
            )
                .takeIf { scope ->
                    listOf(
                        scope.userId,
                        scope.tenantId,
                        scope.workspaceId,
                        scope.membershipId
                    ).all(String::isNotBlank)
                }
        }

    private fun DispatchDeliveryInstructionsSnapshot.isValidFor(deliveryId: String): Boolean =
        this.deliveryId == deliveryId && deliveryVersion >= 0 && instructionSetVersion >= 0 &&
            instructions.map { it.id.lowercase() }.distinct().size == instructions.size &&
            instructions.all {
                it.instructionVersion > 0 && it.critical == it.kind.critical &&
                    (it.sourceKind == null || it.sourceKind in ALLOWED_SOURCE_KINDS)
            }

    private fun DispatchDeliveryInstructionReceipt.isFor(
        intent: DispatchDeliveryInstructionIntent
    ): Boolean =
        deliveryId == intent.deliveryId && kind == intent.kind && content == intent.content &&
            critical == kind.critical && deliveryVersion == intent.expectedDeliveryVersion + 1 &&
            instructionVersion > 0 && instructionSetVersion > 0 &&
            (
                intent.instructionId == null ||
                    instructionId.equals(intent.instructionId, ignoreCase = true)
                )

    private fun InstructionsGatewayResult.toStatus() = when (this) {
        is InstructionsGatewayResult.Snapshot,
        is InstructionsGatewayResult.Published -> DeliveryInstructionsStatus.ServiceUnavailable

        is InstructionsGatewayResult.Rejected -> DeliveryInstructionsStatus.Conflict

        InstructionsGatewayResult.StaleVersion -> DeliveryInstructionsStatus.StaleVersion

        InstructionsGatewayResult.NotFound -> DeliveryInstructionsStatus.NotFound

        InstructionsGatewayResult.UnknownOutcome -> DeliveryInstructionsStatus.UnknownOutcome

        InstructionsGatewayResult.NetworkUnavailable ->
            DeliveryInstructionsStatus.NetworkUnavailable

        InstructionsGatewayResult.ServiceUnavailable ->
            DeliveryInstructionsStatus.ServiceUnavailable

        InstructionsGatewayResult.PermissionDenied -> DeliveryInstructionsStatus.PermissionDenied

        InstructionsGatewayResult.ContextInvalidated ->
            DeliveryInstructionsStatus.ContextInvalidated

        InstructionsGatewayResult.SessionInvalidated ->
            DeliveryInstructionsStatus.SessionInvalidated
    }

    private companion object {
        const val DISPATCH_READ = "dispatch.read"
        const val DISPATCH_SCHEDULE = "dispatch.schedule"
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val ALLOWED_SOURCE_KINDS =
            setOf("BUYER", "CUSTOMER_REPORTED_BY_SALES", "OPERATIONAL_DISPATCH")
        val INVALIDATED_STATUSES = setOf(
            DeliveryInstructionsStatus.ContextInvalidated,
            DeliveryInstructionsStatus.SessionInvalidated
        )
    }
}
