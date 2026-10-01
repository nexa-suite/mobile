package com.nexa.mobile.operations

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.commercial.CommercialAuthority
import com.nexa.mobile.operations.commercial.CustomerInstructionGateway
import com.nexa.mobile.operations.commercial.CustomerInstructionResult
import com.nexa.mobile.operations.commercial.CustomerInstructionCommand
import com.nexa.mobile.operations.commercial.CustomerInstructionSnapshot
import com.nexa.mobile.operations.commercial.CustomerInstructionRow
import com.nexa.mobile.operations.commercial.CustomerInstructionStore
import com.nexa.mobile.operations.commercial.CustomerInstructionStored
import com.nexa.mobile.operations.commercial.CustomerDeliveryInstructionsViewModel
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.network.CustomerDeliveryInstructionsNetworkResult
import com.nexa.mobile.operations.core.network.NexaCustomerDeliveryInstructionsGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class OperationsCustomerInstructionGateway @Inject constructor(
    private val sessions: SessionCoordinator, calls: ProtectedCallExecutor
) : CustomerInstructionGateway {
    private val api = NexaCustomerDeliveryInstructionsGateway(calls)
    override suspend fun read(authority: CommercialAuthority, orderId: String): CustomerInstructionResult = execute(authority, false) { api.read(orderId) }
    override suspend fun publish(authority: CommercialAuthority, command: CustomerInstructionCommand): CustomerInstructionResult = execute(authority, true) {
        api.publish(command.orderId, command.version, command.key,
            NexaCustomerDeliveryInstructionsGateway.body(command.instructionId, command.kind, command.content, command.source))
    }
    private suspend fun execute(authority: CommercialAuthority, mutation: Boolean,
        call: suspend () -> CustomerDeliveryInstructionsNetworkResult): CustomerInstructionResult {
        val permission = if (mutation) "client.manage" else "sales.order.read"
        if (!current(authority) || authority.permissions.none { it == permission || it == "sales:write" || (!mutation && it == "sales:read") })
            return CustomerInstructionResult.Failed("Permiso o contexto no vigente.")
        val lease = sessions.currentAccess() ?: return CustomerInstructionResult.Failed("Sesión no vigente.")
        val result = call()
        if (!sessions.isEpochCurrent(lease.epoch) || !current(authority))
            return CustomerInstructionResult.Failed("Contexto cambió; recargar antes de continuar.", mutation)
        return when (result) {
            is CustomerDeliveryInstructionsNetworkResult.Current -> result.snapshot.let { value ->
                CustomerInstructionResult.Current(CustomerInstructionSnapshot(value.salesOrderId, value.version, value.editable,
                    value.instructions.map { CustomerInstructionRow(it.id, it.instructionVersion, it.kind, it.content,
                        it.sourceKind, it.recordedByMembershipId, it.recordedAt) }))
            }
            is CustomerDeliveryInstructionsNetworkResult.Failed -> CustomerInstructionResult.Failed(
                result.code ?: "Servicio no disponible.", result.unknownOutcome)
        }
    }
    private fun current(authority: CommercialAuthority): Boolean {
        val verified = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active && authority.authorityEpoch > 0 && verified.hasAuthorizedContext &&
            verified.userId == authority.userId && verified.tenantId == authority.tenantId &&
            verified.workspaceId == authority.workspaceId && verified.membershipId == authority.membershipId && verified.permissions == authority.permissions
    }
}
@Singleton
internal class AppCustomerInstructionStore @Inject constructor(@ApplicationContext context: Context) : CustomerInstructionStore {
    private val local = AndroidScopedMetadataStore(context, ScopedMetadataPurpose.CustomerDeliveryInstruction)
    private val mutex = Mutex()
    override suspend fun load(authority: CommercialAuthority): CustomerInstructionStored = mutex.withLock {
        when (val stored = local.load(authority.scope())) {
            ScopedMetadataRead.Unavailable -> CustomerInstructionStored.Unavailable
            is ScopedMetadataRead.Value -> if (stored.payload == null) CustomerInstructionStored.Loaded(null)
                else decode(stored.payload!!)?.let { CustomerInstructionStored.Loaded(it) } ?: CustomerInstructionStored.Unavailable
        }
    }
    override suspend fun save(authority: CommercialAuthority, command: CustomerInstructionCommand): Boolean = mutex.withLock {
        when (val stored = local.load(authority.scope())) {
            ScopedMetadataRead.Unavailable -> false
            is ScopedMetadataRead.Value -> {
                if (stored.payload != null && decode(stored.payload!!) != command) false
                else local.save(authority.scope(), encode(command))
            }
        }
    }
    override suspend fun clear(authority: CommercialAuthority, key: String): Boolean = mutex.withLock {
        when (val stored = local.load(authority.scope())) {
            ScopedMetadataRead.Unavailable -> false
            is ScopedMetadataRead.Value -> if (stored.payload == null) true
                else if (decode(stored.payload!!)?.key == key) local.clear(authority.scope()) else false
        }
    }
    private fun CommercialAuthority.scope() = ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)
    private fun encode(value: CustomerInstructionCommand): String = JsonObject(mapOf(
        "schema" to JsonPrimitive(1), "order" to JsonPrimitive(value.orderId), "version" to JsonPrimitive(value.version),
        "id" to JsonPrimitive(value.instructionId), "kind" to JsonPrimitive(value.kind), "content" to JsonPrimitive(value.content),
        "source" to JsonPrimitive(value.source), "key" to JsonPrimitive(value.key))).toString()
    private fun decode(payload: String): CustomerInstructionCommand? = try {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root["schema"]!!.jsonPrimitive.longOrNull == 1L)
        fun text(key: String) = root[key]!!.jsonPrimitive.also { require(it.isString) }.content
        CustomerInstructionCommand(text("order"), root["version"]!!.jsonPrimitive.longOrNull!!,
            text("id"), text("kind"), text("content"), text("source"), text("key")).also {
            require(it.version >= 0 && it.key.isNotBlank())
            NexaCustomerDeliveryInstructionsGateway.body(it.instructionId, it.kind, it.content, it.source)
            require(NexaCustomerDeliveryInstructionsGateway.validUuid(it.orderId))
        }
    } catch (_: Exception) { null }
}
internal class CustomerDeliveryInstructionsViewModelFactory @Inject constructor(
    private val gateway: OperationsCustomerInstructionGateway, private val store: AppCustomerInstructionStore
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CustomerDeliveryInstructionsViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return CustomerDeliveryInstructionsViewModel(gateway, store) as T
    }
}
