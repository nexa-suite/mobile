package com.nexa.mobile.operations.data

import android.content.Context
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.network.CustomerNetworkResult
import com.nexa.mobile.operations.core.network.NexaCustomerGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.feature.commercial.application.FieldVisitGateway
import com.nexa.mobile.operations.feature.commercial.application.FieldVisitResult
import com.nexa.mobile.operations.feature.commercial.application.FieldVisitStore
import com.nexa.mobile.operations.feature.commercial.model.CommercialAuthority
import com.nexa.mobile.operations.feature.commercial.model.CustomerRelationship
import com.nexa.mobile.operations.feature.commercial.model.FieldVisitIntent
import com.nexa.mobile.operations.feature.commercial.model.FieldVisitRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class OperationsFieldVisitGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val calls: ProtectedCallExecutor
) : FieldVisitGateway {
    private val customers = NexaCustomerGateway(calls)
    private fun current(a: CommercialAuthority): Boolean {
        val v = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active && a.canReadCustomers &&
            v.hasAuthorizedContext &&
            v.userId == a.userId && v.tenantId == a.tenantId && v.workspaceId == a.workspaceId &&
            v.membershipId == a.membershipId && v.permissions == a.permissions
    }
    override suspend fun customer(
        authority: CommercialAuthority,
        id: String
    ): CustomerRelationship? {
        if (!current(authority)) return null
        val lease = sessions.currentAccess() ?: return null
        val value = (customers.detail(id) as? CustomerNetworkResult.Detail)?.value ?: return null
        if (!current(authority) || !sessions.isEpochCurrent(lease.epoch)) return null
        return CustomerRelationship(
            value.id,
            value.code,
            value.businessName,
            value.commercialName,
            value.status == "ACTIVE",
            value.buyerMembershipId != null,
            value.version
        )
    }
    override fun body(purpose: String, followUp: String, occurredAt: Instant) = buildJsonObject {
        put("purpose", purpose)
        put("outcome", followUp)
        put("occurredAt", occurredAt.toString())
    }.toString()
    override suspend fun record(
        authority: CommercialAuthority,
        intent: FieldVisitIntent
    ): FieldVisitResult {
        if (!current(authority) ||
            authority.permissions.none { it == "client.manage" || it == "sales:write" }
        ) {
            return FieldVisitResult.Denied
        }
        val lease = sessions.currentAccess() ?: return FieldVisitResult.Denied
        val customer = customer(authority, intent.customerId) ?: return FieldVisitResult.Denied
        if (!customer.active || !current(authority) ||
            !sessions.isEpochCurrent(lease.epoch)
        ) {
            return FieldVisitResult.Denied
        }
        if (runCatching {
                UUID.fromString(intent.customerId)
            }.isFailure
        ) {
            return FieldVisitResult.Denied
        }
        val result = calls.execute(
            ProtectedRequest(
                ProtectedMethod.POST,
                "/api/v1/client-accounts/${intent.customerId}/field-visits",
                intent.body,
                intent.key,
                "\"${intent.version}\""
            )
        )
        if (!current(authority) ||
            !sessions.isEpochCurrent(lease.epoch)
        ) {
            return FieldVisitResult.UnknownOutcome
        }
        return when (result) {
            is ProtectedResult.Success -> {
                val value = Json.parseToJsonElement(result.body ?: "").jsonObject
                val id = value["id"]?.jsonPrimitive?.content
                if (result.status != 201 || id.isNullOrBlank() ||
                    value["clientAccountId"]?.jsonPrimitive?.content != intent.customerId ||
                    value["recordedByMembershipId"]?.jsonPrimitive?.content !=
                    authority.membershipId
                ) {
                    FieldVisitResult.UnknownOutcome
                } else {
                    FieldVisitResult.Recorded(id)
                }
            }

            is ProtectedResult.Failure -> when (result.error.httpStatus) {
                400, 409, 412, 422 -> FieldVisitResult.Conflict
                401, 403, 404 -> FieldVisitResult.Denied
                else -> FieldVisitResult.UnknownOutcome
            }
        }
    }
}

@Singleton
class AppFieldVisitStore @Inject constructor(@ApplicationContext context: Context) :
    FieldVisitStore {
    private val local = AndroidScopedMetadataStore(context, ScopedMetadataPurpose.FieldVisit)
    private val mutex = Mutex()
    private fun CommercialAuthority.scope() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)
    override suspend fun load(authority: CommercialAuthority): FieldVisitRecord? = mutex.withLock {
        val read =
            local.load(authority.scope()) as? ScopedMetadataRead.Value ?: return@withLock null
        val payload = read.payload ?: return@withLock FieldVisitRecord()
        val record = decode(payload) ?: return@withLock null
        val intent = record.intent
        if (intent?.outcome == "Pending") {
            val recovered = record.copy(intent = intent.copy(outcome = "UnknownOutcome"))
            if (local.save(authority.scope(), encode(recovered))) recovered else null
        } else {
            record
        }
    }
    override suspend fun save(authority: CommercialAuthority, record: FieldVisitRecord): Boolean =
        mutex.withLock {
            val read =
                local.load(authority.scope()) as? ScopedMetadataRead.Value ?: return@withLock false
            val payload = read.payload
            val prior = payload?.let(::decode)
            if (payload != null && prior == null) return@withLock false
            val intent = prior?.intent
            val nextIntent = record.intent
            if (intent != null && intent.outcome !in setOf("Recorded", "Conflict") &&
                (
                    nextIntent?.key != intent.key || nextIntent.body != intent.body ||
                        nextIntent.version != intent.version ||
                        nextIntent.customerId != intent.customerId
                    )
            ) {
                return@withLock false
            }
            local.save(authority.scope(), encode(record))
        }
    private fun encode(r: FieldVisitRecord) = buildJsonObject {
        put("schema", 1)
        put("customer", r.customerId)
        put("purpose", r.purpose)
        put("followUp", r.followUp)
        r.intent?.let { i ->
            putJsonObject("intent") {
                put("key", i.key)
                put("customer", i.customerId)
                put("version", i.version)
                put("body", i.body)
                put("outcome", i.outcome)
                i.receiptId?.let { put("receipt", it) }
            }
        }
    }.toString()
    private fun decode(payload: String): FieldVisitRecord? = runCatching {
        val v = Json.parseToJsonElement(payload).jsonObject
        fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        require(v.getValue("schema").jsonPrimitive.int == 1)
        val intent = v["intent"]?.jsonObject?.let { i ->
            require(i.text("outcome") in setOf("Pending", "UnknownOutcome", "Recorded", "Conflict"))
            require(i.text("key").isNotBlank())
            UUID.fromString(i.text("customer"))
            require(Json.parseToJsonElement(i.text("body")) is JsonObject)
            FieldVisitIntent(
                i.text("key"),
                i.text("customer"),
                i.getValue("version").jsonPrimitive.long,
                i.text("body"),
                i.text("outcome"),
                i["receipt"]?.jsonPrimitive?.contentOrNull
            )
        }
        FieldVisitRecord(v.text("customer"), v.text("purpose"), v.text("followUp"), intent)
    }.getOrNull()
}
