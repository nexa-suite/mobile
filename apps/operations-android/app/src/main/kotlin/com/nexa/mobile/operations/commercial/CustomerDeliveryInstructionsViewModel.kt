package com.nexa.mobile.operations.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Presentation values, never transport DTOs or client authorization decisions. */
data class CustomerInstructionRow(val id: String, val version: Long, val kind: String,
    val content: String, val source: String, val recordedBy: String, val recordedAt: String)
data class CustomerInstructionSnapshot(val orderId: String, val version: Long, val editable: Boolean,
    val rows: List<CustomerInstructionRow>)
data class CustomerInstructionCommand(val orderId: String, val version: Long, val instructionId: String,
    val kind: String, val content: String, val source: String, val key: String) {
    override fun toString() = "CustomerInstructionCommand(REDACTED)"
}
sealed interface CustomerInstructionResult {
    data class Current(val snapshot: CustomerInstructionSnapshot) : CustomerInstructionResult
    data class Failed(val message: String, val unknown: Boolean = false) : CustomerInstructionResult
}
interface CustomerInstructionGateway {
    suspend fun read(authority: CommercialAuthority, orderId: String): CustomerInstructionResult
    suspend fun publish(authority: CommercialAuthority, command: CustomerInstructionCommand): CustomerInstructionResult
}
sealed interface CustomerInstructionStored {
    data class Loaded(val command: CustomerInstructionCommand?) : CustomerInstructionStored
    data object Unavailable : CustomerInstructionStored
}
interface CustomerInstructionStore {
    suspend fun load(authority: CommercialAuthority): CustomerInstructionStored
    suspend fun save(authority: CommercialAuthority, command: CustomerInstructionCommand): Boolean
    suspend fun clear(authority: CommercialAuthority, key: String): Boolean
}
data class CustomerInstructionsState(val orderId: String = "", val snapshot: CustomerInstructionSnapshot? = null,
    val instructionId: String? = null, val kind: String = "NORMAL", val content: String = "", val source: String = "",
    val pending: Boolean = false, val recoverable: CustomerInstructionCommand? = null,
    val storageAvailable: Boolean = false, val message: String? = null) {
    val canPublish: Boolean get() = !pending && storageAvailable && recoverable == null && snapshot?.editable == true &&
        snapshot.orderId == orderId && content.isNotBlank() && content.length <= 2000 && source.isNotBlank() && source.length <= 500
}
class CustomerDeliveryInstructionsViewModel(private val gateway: CustomerInstructionGateway,
    private val store: CustomerInstructionStore) : ViewModel() {
    private val mutable = MutableStateFlow(CustomerInstructionsState())
    val state = mutable.asStateFlow()
    private var authority: CommercialAuthority? = null
    private var generation = 0L
    fun activate(value: CommercialAuthority, orderId: String = "") {
        deactivate(); authority = value.copy(permissions = value.permissions.toSet())
        mutable.value = state.value.copy(orderId = orderId, pending = true)
        val current = authority!!; val request = generation
        viewModelScope.launch {
            val stored = store.load(current)
            if (generation != request || authority != current) return@launch
            mutable.value = when (stored) {
                is CustomerInstructionStored.Loaded -> state.value.copy(storageAvailable = true, pending = false,
                    recoverable = stored.command, orderId = stored.command?.orderId ?: orderId,
                    message = if (stored.command == null) null else "Resultado pendiente. Reintentar mismo comando.")
                CustomerInstructionStored.Unavailable -> state.value.copy(pending = false, message = "Almacenamiento protegido no disponible.")
            }
        }
    }
    fun orderChanged(value: String) {
        if (state.value.pending || state.value.recoverable != null) return
        generation++; mutable.value = CustomerInstructionsState(orderId = value.trim(), storageAvailable = state.value.storageAvailable)
    }
    fun kindChanged(value: String) { if (!state.value.pending && state.value.recoverable == null && value in kinds) mutable.value = state.value.copy(kind = value) }
    fun contentChanged(value: String) { if (!state.value.pending && state.value.recoverable == null) mutable.value = state.value.copy(content = value.take(2000)) }
    fun sourceChanged(value: String) { if (!state.value.pending && state.value.recoverable == null) mutable.value = state.value.copy(source = value.take(500)) }
    fun edit(row: CustomerInstructionRow) {
        if (!state.value.pending && state.value.recoverable == null && state.value.snapshot?.editable == true)
            mutable.value = state.value.copy(instructionId = row.id, kind = row.kind, content = row.content, source = "")
    }
    fun newInstruction() { if (!state.value.pending && state.value.recoverable == null) mutable.value = state.value.copy(instructionId = null, content = "", source = "", kind = "NORMAL") }
    fun refresh() {
        val current = authority ?: return
        if (state.value.pending) return
        val id = state.value.orderId; val request = ++generation
        mutable.value = state.value.copy(pending = true, snapshot = null, message = null)
        viewModelScope.launch {
            val result = safe { gateway.read(current, id) }
            if (request != generation || authority != current) return@launch
            mutable.value = when (result) {
                is CustomerInstructionResult.Current -> state.value.copy(pending = false, snapshot = result.snapshot)
                is CustomerInstructionResult.Failed -> state.value.copy(pending = false, message = result.message)
            }
        }
    }
    fun publish() {
        val input = state.value
        if (!input.canPublish) return
        execute(CustomerInstructionCommand(input.orderId, input.snapshot!!.version,
            input.instructionId ?: UUID.randomUUID().toString(), input.kind, input.content.trim(), input.source.trim(), UUID.randomUUID().toString()), true)
    }
    fun retry() { state.value.recoverable?.let { execute(it, false) } }
    private fun execute(command: CustomerInstructionCommand, save: Boolean) {
        val current = authority ?: return
        if (state.value.pending) return
        val request = ++generation
        mutable.value = state.value.copy(pending = true, message = null)
        viewModelScope.launch {
            if (save && !store.save(current, command)) {
                if (request == generation && authority == current) mutable.value = state.value.copy(pending = false,
                    storageAvailable = false, message = "No se envió: almacenamiento protegido no disponible.")
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
                    mutable.value = state.value.copy(pending = false, snapshot = result.snapshot,
                        recoverable = if (cleared) null else command, instructionId = null, content = "", source = "",
                        message = if (cleared) "Instrucción registrada." else "Registrada; recuperar comando antes de otra edición.")
                }
                is CustomerInstructionResult.Failed -> {
                    val cleared = !result.unknown && store.clear(current, command.key)
                    if (request != generation || authority != current) return@launch
                    mutable.value = state.value.copy(pending = false, recoverable = if (cleared) null else command,
                        snapshot = null, message = result.message)
                }
            }
        }
    }
    private suspend fun safe(block: suspend () -> CustomerInstructionResult): CustomerInstructionResult = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CustomerInstructionResult.Failed("Servicio no disponible; conservar comando si fue enviado.", true) }
    fun deactivate() { generation++; authority = null; mutable.value = CustomerInstructionsState() }
    companion object { val kinds = listOf("NORMAL", "COLD_CHAIN", "ACCESS_RESTRICTION", "SPECIAL_UNLOADING", "CUSTOMER_SAFETY", "GOODS_HANDLING") }
}
