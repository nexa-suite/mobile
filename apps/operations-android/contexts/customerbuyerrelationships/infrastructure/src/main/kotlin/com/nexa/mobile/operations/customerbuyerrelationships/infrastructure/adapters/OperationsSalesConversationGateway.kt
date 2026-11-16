package com.nexa.mobile.operations.customerbuyerrelationships.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesConversationBuyer
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesConversationGateway
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesConversationMessage
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesConversationProjection
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesConversationRead
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesMessageSendResult
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesMessagingAuthority
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesPendingMessage
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesPendingMessageRead
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class OperationsSalesConversationGateway @Inject constructor(
    @ApplicationContext context: Context,
    private val sessions: SessionCoordinator,
    private val calls: ProtectedCallExecutor
) : SalesConversationGateway {
    private val store =
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.SalesMessageCommand)

    override suspend fun load(
        authority: SalesMessagingAuthority,
        accountId: String
    ): SalesConversationRead {
        if (!validId(accountId)) return SalesConversationRead.Unavailable
        if (!current(authority, "client.read")) return SalesConversationRead.PermissionDenied
        val lease = sessions.currentAccess() ?: return SalesConversationRead.ContextInvalidated
        return try {
            val buyers = loadBuyers(authority, accountId, lease.epoch)
                ?: return stateAfterFailure(authority)
            val messages = loadMessages(authority, accountId, lease.epoch)
                ?: return stateAfterFailure(authority)
            if (!current(authority, "client.read") || !sessions.isEpochCurrent(lease.epoch)) {
                SalesConversationRead.ContextInvalidated
            } else {
                SalesConversationRead.Current(SalesConversationProjection(buyers, messages))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            stateAfterFailure(authority)
        }
    }

    override suspend fun pending(authority: SalesMessagingAuthority): SalesPendingMessageRead {
        if (!current(authority, "client.read")) return SalesPendingMessageRead.PermissionDenied
        return try {
            when (val result = store.load(scope(authority))) {
                ScopedMetadataRead.Unavailable -> SalesPendingMessageRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload = result.payload
                    if (payload == null) {
                        SalesPendingMessageRead.Current(null)
                    } else {
                        val pending = runCatching {
                            val value = Json.parseToJsonElement(payload).jsonObject
                            SalesPendingMessage(
                                accountId = value.getValue("account").jsonPrimitive.content,
                                recipientMembershipId = value.getValue(
                                    "recipient"
                                ).jsonPrimitive.content,
                                body = value.getValue("body").jsonPrimitive.content,
                                idempotencyKey = value.getValue("key").jsonPrimitive.content
                            )
                        }.getOrNull()
                        if (pending == null || !validId(pending.accountId) ||
                            !validId(pending.recipientMembershipId) ||
                            !validId(pending.idempotencyKey) || pending.body.isBlank() ||
                            pending.body.length > MAX_MESSAGE_LENGTH
                        ) {
                            SalesPendingMessageRead.Unavailable
                        } else {
                            SalesPendingMessageRead.Current(pending)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            stateAfterPendingFailure(authority)
        }
    }

    override suspend fun send(
        authority: SalesMessagingAuthority,
        message: SalesPendingMessage,
        isRetry: Boolean
    ): SalesMessageSendResult {
        if (!current(authority, "client.manage") || !valid(message)) {
            return SalesMessageSendResult.PermissionDenied
        }
        val lease = sessions.currentAccess() ?: return SalesMessageSendResult.ContextInvalidated
        return try {
            if (isRetry) {
                val existing = pending(authority)
                if (existing !is SalesPendingMessageRead.Current || existing.value != message) {
                    return existing.toSendResult()
                }
            } else {
                val existing = pending(authority)
                if (existing !is SalesPendingMessageRead.Current || existing.value != null) {
                    return existing.toSendResult()
                }
                val payload = buildJsonObject {
                    put("account", message.accountId)
                    put("recipient", message.recipientMembershipId)
                    put("body", message.body)
                    put("key", message.idempotencyKey)
                }.toString()
                if (!store.save(scope(authority), payload)) {
                    return SalesMessageSendResult.Unavailable
                }
            }
            if (!current(authority, "client.manage") || !sessions.isEpochCurrent(lease.epoch)) {
                return SalesMessageSendResult.ContextInvalidated
            }
            val payload = buildJsonObject {
                put("recipientBuyerMembershipId", message.recipientMembershipId)
                put("body", message.body)
            }.toString()
            val result = calls.execute(
                ProtectedRequest(
                    ProtectedMethod.POST,
                    "/api/v1/client-accounts/${message.accountId}/messages",
                    payload,
                    idempotencyKey = message.idempotencyKey
                )
            )
            if (!current(authority, "client.manage") || !sessions.isEpochCurrent(lease.epoch)) {
                return SalesMessageSendResult.ContextInvalidated
            }
            if (result !is ProtectedResult.Success || result.status !in setOf(200, 201) ||
                !salesMessageResponseMatches(result.body, message)
            ) {
                return result.toSendResult()
            }
            if (store.clear(scope(authority))) {
                SalesMessageSendResult.Confirmed
            } else {
                SalesMessageSendResult.Unavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SalesMessageSendResult.Unavailable
        }
    }

    private suspend fun loadBuyers(
        authority: SalesMessagingAuthority,
        accountId: String,
        epoch: Long
    ): List<SalesConversationBuyer>? {
        val buyers = mutableListOf<SalesConversationBuyer>()
        for (page in 0 until MAX_PAGES) {
            val response = calls.execute(
                ProtectedRequest(
                    ProtectedMethod.GET,
                    "/api/v1/client-accounts/$accountId/buyer-memberships?page=$page&size=$PAGE_SIZE"
                )
            )
            if (response !is ProtectedResult.Success || response.status != 200 ||
                !current(authority, "client.read") || !sessions.isEpochCurrent(epoch)
            ) {
                return null
            }
            val obj = runCatching { Json.parseToJsonElement(response.body ?: "").jsonObject }
                .getOrNull() ?: return null
            val values = runCatching { obj.getValue("items").jsonArray }.getOrNull() ?: return null
            for (value in values) {
                val buyer = runCatching { value.jsonObject }.getOrNull() ?: return null
                if (buyer["status"]?.jsonPrimitive?.contentOrNull != "ACTIVE") continue
                val id = buyer["membershipId"]?.jsonPrimitive?.contentOrNull ?: return null
                if (!validId(id)) return null
                val name = buyer["displayName"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?: buyer["email"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                    ?: return null
                buyers += SalesConversationBuyer(id, name)
            }
            val total = obj["total"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
            if ((page + 1L) * PAGE_SIZE >= total) return buyers.toList()
            if (values.isEmpty() || page == MAX_PAGES - 1) return null
        }
        return null
    }

    private suspend fun loadMessages(
        authority: SalesMessagingAuthority,
        accountId: String,
        epoch: Long
    ): List<SalesConversationMessage>? {
        val messages = mutableListOf<SalesConversationMessage>()
        var cursor: String? = null
        for (page in 0 until MAX_PAGES) {
            val query = cursor?.let { "&afterMessageId=$it" }.orEmpty()
            val response = calls.execute(
                ProtectedRequest(
                    ProtectedMethod.GET,
                    "/api/v1/client-accounts/$accountId/messages?size=$PAGE_SIZE$query"
                )
            )
            if (response !is ProtectedResult.Success || response.status != 200 ||
                !current(authority, "client.read") || !sessions.isEpochCurrent(epoch)
            ) {
                return null
            }
            val obj = runCatching { Json.parseToJsonElement(response.body ?: "").jsonObject }
                .getOrNull() ?: return null
            val values = runCatching {
                obj.getValue("items").jsonArray.map { value ->
                    val item = value.jsonObject
                    require(item.getValue("clientAccountId").jsonPrimitive.content == accountId)
                    SalesConversationMessage(
                        item.getValue("senderKind").jsonPrimitive.content,
                        item.getValue("body").jsonPrimitive.content,
                        item.getValue("createdAt").jsonPrimitive.content
                    )
                }
            }.getOrNull() ?: return null
            messages += values
            val hasMore = obj["hasMore"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull() ?: return null
            if (!hasMore) return messages.toList()
            val next = obj["nextAfterMessageId"]?.jsonPrimitive?.contentOrNull ?: return null
            if (!validId(next) || next == cursor || values.isEmpty() || page == MAX_PAGES - 1) {
                return null
            }
            cursor = next
        }
        return null
    }

    private fun current(authority: SalesMessagingAuthority, permission: String): Boolean {
        val verified = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active && authority.authorityEpoch > 0 &&
            permission in authority.permissions && verified.hasAuthorizedContext &&
            verified.permissions == authority.permissions && verified.userId == authority.userId &&
            verified.tenantId == authority.tenantId &&
            verified.workspaceId == authority.workspaceId &&
            verified.membershipId == authority.membershipId
    }

    private suspend fun stateAfterFailure(authority: SalesMessagingAuthority) =
        if (current(authority, "client.read")) {
            SalesConversationRead.Unavailable
        } else {
            SalesConversationRead.ContextInvalidated
        }

    private suspend fun stateAfterPendingFailure(authority: SalesMessagingAuthority) =
        if (current(authority, "client.read")) {
            SalesPendingMessageRead.Unavailable
        } else {
            SalesPendingMessageRead.ContextInvalidated
        }

    private fun scope(authority: SalesMessagingAuthority) = ScopedMetadataScope(
        authority.userId,
        authority.tenantId,
        authority.workspaceId,
        authority.membershipId
    )

    private fun valid(message: SalesPendingMessage) =
        validId(message.accountId) && validId(message.recipientMembershipId) &&
            validId(message.idempotencyKey) && message.body.isNotBlank() &&
            message.body.length <= MAX_MESSAGE_LENGTH

    private fun validId(value: String) = runCatching { UUID.fromString(value) }.isSuccess

    private fun SalesPendingMessageRead.toSendResult() = when (this) {
        is SalesPendingMessageRead.Current -> SalesMessageSendResult.Unavailable
        SalesPendingMessageRead.PermissionDenied -> SalesMessageSendResult.PermissionDenied
        SalesPendingMessageRead.ContextInvalidated -> SalesMessageSendResult.ContextInvalidated
        SalesPendingMessageRead.Unavailable -> SalesMessageSendResult.Unavailable
    }

    private fun ProtectedResult.toSendResult() = when (this) {
        is ProtectedResult.Success -> SalesMessageSendResult.Unavailable

        is ProtectedResult.Failure -> when (error.kind) {
            FailureKind.AuthenticationRequired -> SalesMessageSendResult.ContextInvalidated
            FailureKind.AuthorizationFailure -> SalesMessageSendResult.PermissionDenied
            else -> SalesMessageSendResult.Unavailable
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 20
        const val MAX_MESSAGE_LENGTH = 4000
    }
}

internal fun salesMessageResponseMatches(body: String?, message: SalesPendingMessage): Boolean =
    runCatching {
        val result = Json.parseToJsonElement(body ?: "").jsonObject
        UUID.fromString(result.getValue("id").jsonPrimitive.content)
        result.getValue("clientAccountId").jsonPrimitive.content.equals(message.accountId, true) &&
            result.getValue("buyerMembershipId").jsonPrimitive.content.equals(
                message.recipientMembershipId,
                true
            ) && result.getValue("senderKind").jsonPrimitive.content == "SALES" &&
            result.getValue("body").jsonPrimitive.content == message.body
    }.getOrDefault(false)
