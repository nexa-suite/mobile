package com.nexa.mobile.operations.customerbuyerrelationships.application.commercial

/** Verified Buyer relationship scope projected by the Operations composition root. */
data class SalesMessagingAuthority(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>,
    val authorityEpoch: Long
) {
    val canReadMessages: Boolean
        get() = authorityEpoch > 0 && "client.read" in permissions

    val canManageMessages: Boolean
        get() = authorityEpoch > 0 && "client.manage" in permissions

    override fun toString(): String = "SalesMessagingAuthority(REDACTED)"
}

data class SalesConversationBuyer(val membershipId: String, val displayName: String)

data class SalesConversationMessage(
    val sender: String,
    val body: String,
    val createdAt: String
) {
    override fun toString(): String = "SalesConversationMessage(body=REDACTED)"
}

data class SalesPendingMessage(
    val accountId: String,
    val recipientMembershipId: String,
    val body: String,
    val idempotencyKey: String
) {
    override fun toString(): String = "SalesPendingMessage(body=REDACTED)"
}

data class SalesConversationProjection(
    val buyers: List<SalesConversationBuyer>,
    val messages: List<SalesConversationMessage>
)

sealed interface SalesConversationRead {
    data class Current(val value: SalesConversationProjection) : SalesConversationRead
    data object PermissionDenied : SalesConversationRead
    data object ContextInvalidated : SalesConversationRead
    data object Unavailable : SalesConversationRead
}

sealed interface SalesPendingMessageRead {
    data class Current(val value: SalesPendingMessage?) : SalesPendingMessageRead
    data object PermissionDenied : SalesPendingMessageRead
    data object ContextInvalidated : SalesPendingMessageRead
    data object Unavailable : SalesPendingMessageRead
}

enum class SalesMessageSendResult {
    Confirmed,
    PermissionDenied,
    ContextInvalidated,
    Unavailable
}

interface SalesConversationGateway {
    suspend fun load(
        authority: SalesMessagingAuthority,
        accountId: String
    ): SalesConversationRead

    suspend fun pending(authority: SalesMessagingAuthority): SalesPendingMessageRead

    suspend fun send(
        authority: SalesMessagingAuthority,
        message: SalesPendingMessage,
        isRetry: Boolean
    ): SalesMessageSendResult
}
