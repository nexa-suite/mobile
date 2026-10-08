package com.nexa.mobile.operations.fulfillmentdelivery.presentation.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.commercial.CustomerInstructionGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.commercial.CustomerInstructionResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.commercial.CustomerInstructionStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.commercial.CustomerInstructionStored
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.commercial.CustomerInstructionCommand
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.commercial.CustomerInstructionRow
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.commercial.CustomerInstructionSnapshot
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.commercial.CommercialAuthority
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CustomerInstructionsNotice {
    ProtectedStorageUnavailable,
    SubmissionBlocked,
    PermissionUnavailable,
    SessionUnavailable,
    ContextChanged,
    ServiceUnavailable,
    Registered,
    RegisteredNeedsRecovery,
    UnknownOutcome,
    OperationUnavailable
}

data class CustomerInstructionsState(
    val orderId: String = "",
    val snapshot: CustomerInstructionSnapshot? = null,
    val instructionId: String? = null,
    val kind: String = "NORMAL",
    val content: String = "",
    val source: String = "",
    val pending: Boolean = false,
    val recoverable: CustomerInstructionCommand? = null,
    val storageAvailable: Boolean = false,
    val message: CustomerInstructionsNotice? = null
) {
    val canPublish: Boolean get() = !pending && storageAvailable && recoverable == null &&
        snapshot?.editable == true &&
        snapshot.orderId == orderId && content.isNotBlank() && content.length <= 2000 &&
        source.isNotBlank() &&
        source.length <= 500
}

class CustomerDeliveryInstructionsViewModel(
    private val gateway: CustomerInstructionGateway,
    private val store: CustomerInstructionStore
) : ViewModel() {
    private val mutable = MutableStateFlow(CustomerInstructionsState())
    val state = mutable.asStateFlow()
    private var authority: CommercialAuthority? = null
    private var generation = 0L
    fun activate(value: CommercialAuthority, orderId: String = "") {
        deactivate()
        authority = value.copy(permissions = value.permissions.toSet())
        mutable.value = state.value.copy(orderId = orderId, pending = true)
        val current = authority!!
        val request = generation
        viewModelScope.launch {
            val stored = store.load(current)
            if (generation != request || authority != current) return@launch
            mutable.value = when (stored) {
                is CustomerInstructionStored.Loaded -> state.value.copy(
                    storageAvailable = true,
                    pending = false,
                    recoverable = stored.command,
                    orderId = stored.command?.orderId ?: orderId,
                    message = null
                )

                CustomerInstructionStored.Unavailable -> state.value.copy(
                    pending = false,
                    message = CustomerInstructionsNotice.ProtectedStorageUnavailable
                )
            }
        }
    }
    fun orderChanged(value: String) {
        if (state.value.pending || state.value.recoverable != null) return
        generation++
        mutable.value =
            CustomerInstructionsState(
                orderId = value.trim(),
                storageAvailable = state.value.storageAvailable
            )
    }
    fun kindChanged(value: String) {
        if (!state.value.pending && state.value.recoverable == null &&
            value in kinds
        ) {
            mutable.value = state.value.copy(kind = value)
        }
    }
    fun contentChanged(value: String) {
        if (!state.value.pending &&
            state.value.recoverable == null
        ) {
            mutable.value =
                state.value.copy(content = value.take(2000))
        }
    }
    fun sourceChanged(value: String) {
        if (!state.value.pending &&
            state.value.recoverable == null
        ) {
            mutable.value = state.value.copy(source = value.take(500))
        }
    }
    fun edit(row: CustomerInstructionRow) {
        if (!state.value.pending && state.value.recoverable == null &&
            state.value.snapshot?.editable == true
        ) {
            mutable.value =
                state.value.copy(
                    instructionId = row.id,
                    kind = row.kind,
                    content = row.content,
                    source = ""
                )
        }
    }
    fun newInstruction() {
        if (!state.value.pending &&
            state.value.recoverable == null
        ) {
            mutable.value =
                state.value.copy(instructionId = null, content = "", source = "", kind = "NORMAL")
        }
    }
    fun refresh() {
        val current = authority ?: return
        if (state.value.pending) return
        val id = state.value.orderId
        val request = ++generation
        mutable.value = state.value.copy(pending = true, snapshot = null, message = null)
        viewModelScope.launch {
            val result = safe { gateway.read(current, id) }
            if (request != generation || authority != current) return@launch
            mutable.value = when (result) {
                is CustomerInstructionResult.Current -> state.value.copy(
                    pending = false,
                    snapshot = result.snapshot
                )

                is CustomerInstructionResult.Failed -> state.value.copy(
                    pending = false,
                    message = result.notice()
                )
            }
        }
    }
    fun publish() {
        val input = state.value
        if (!input.canPublish) return
        execute(
            CustomerInstructionCommand(
                input.orderId,
                input.snapshot!!.version,
                input.instructionId ?: UUID.randomUUID().toString(),
                input.kind,
                input.content.trim(),
                input.source.trim(),
                UUID.randomUUID().toString()
            ),
            true
        )
    }
    fun retry() {
        state.value.recoverable?.let { execute(it, false) }
    }
    private fun execute(command: CustomerInstructionCommand, save: Boolean) {
        val current = authority ?: return
        if (state.value.pending) return
        val request = ++generation
        mutable.value = state.value.copy(pending = true, message = null)
        viewModelScope.launch {
            if (save && !store.save(current, command)) {
                if (request == generation &&
                    authority == current
                ) {
                    mutable.value = state.value.copy(
                        pending = false,
                        storageAvailable = false,
                        message = CustomerInstructionsNotice.SubmissionBlocked
                    )
                }
                return@launch
            }
            if (request != generation || authority != current) return@launch
            mutable.value = state.value.copy(recoverable = command)
            val result = safe { gateway.publish(current, command) }
            if (request != generation || authority != current) return@launch
            when (result) {
                is CustomerInstructionResult.Current -> {
                    val cleared = store.clear(current, command.key)
                    if (request != generation || authority != current) return@launch
                    mutable.value = state.value.copy(
                        pending = false,
                        snapshot = result.snapshot,
                        recoverable = if (cleared) null else command,
                        instructionId = null,
                        content = "",
                        source = "",
                        message = if (cleared) {
                            CustomerInstructionsNotice.Registered
                        } else {
                            CustomerInstructionsNotice.RegisteredNeedsRecovery
                        }
                    )
                }

                is CustomerInstructionResult.Failed -> {
                    val cleared = !result.unknown && store.clear(current, command.key)
                    if (request != generation || authority != current) return@launch
                    mutable.value =
                        state.value.copy(
                            pending = false,
                            recoverable = if (cleared) null else command,
                            snapshot = null,
                            message = result.notice()
                        )
                }
            }
        }
    }
    private suspend fun safe(
        block: suspend () -> CustomerInstructionResult
    ): CustomerInstructionResult = try {
        block()
    } catch (
        cancelled: CancellationException
    ) {
        throw cancelled
    } catch (
        _: Exception
    ) {
        CustomerInstructionResult.Failed(
            "Servicio no disponible; conservar comando si fue enviado.",
            true
        )
    }

    private fun CustomerInstructionResult.Failed.notice(): CustomerInstructionsNotice = when {
        unknown -> CustomerInstructionsNotice.UnknownOutcome

        message.contains("Permiso o contexto", ignoreCase = true) ->
            CustomerInstructionsNotice.PermissionUnavailable

        message.contains("Sesión", ignoreCase = true) ->
            CustomerInstructionsNotice.SessionUnavailable

        message.contains("Contexto cambió", ignoreCase = true) ->
            CustomerInstructionsNotice.ContextChanged

        message.contains("Servicio no disponible", ignoreCase = true) ->
            CustomerInstructionsNotice.ServiceUnavailable

        else -> CustomerInstructionsNotice.OperationUnavailable
    }

    fun deactivate() {
        generation++
        authority = null
        mutable.value = CustomerInstructionsState()
    }
    companion object {
        val kinds =
            listOf(
                "NORMAL",
                "COLD_CHAIN",
                "ACCESS_RESTRICTION",
                "SPECIAL_UNLOADING",
                "CUSTOMER_SAFETY",
                "GOODS_HANDLING"
            )
    }
}
