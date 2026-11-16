package com.nexa.mobile.operations.notifications.application.push

import com.nexa.mobile.operations.notifications.application.inbox.NotificationsAuthority
import com.nexa.mobile.operations.notifications.application.inbox.NotificationsResult

data class PushSubscriptionRegistration(
    val authority: NotificationsAuthority,
    val installationId: String,
    val platform: String,
    val providerToken: String,
    val idempotencyKey: String
) {
    override fun toString(): String = "PushSubscriptionRegistration(REDACTED)"
}

interface PushSubscriptionRegistrationGateway {
    suspend fun register(registration: PushSubscriptionRegistration): NotificationsResult<Unit>
}
