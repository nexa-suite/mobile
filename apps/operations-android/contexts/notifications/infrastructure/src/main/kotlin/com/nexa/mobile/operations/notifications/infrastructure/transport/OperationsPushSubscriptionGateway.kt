package com.nexa.mobile.operations.notifications.infrastructure.transport

import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.notifications.application.inbox.NotificationsAuthority
import com.nexa.mobile.operations.notifications.application.inbox.NotificationsResult
import com.nexa.mobile.operations.notifications.application.push.PushSubscriptionRegistration
import com.nexa.mobile.operations.notifications.application.push.PushSubscriptionRegistrationGateway
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class OperationsPushSubscriptionGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val calls: ProtectedCallExecutor
) : PushSubscriptionRegistrationGateway {
    override suspend fun register(
        registration: PushSubscriptionRegistration
    ): NotificationsResult<Unit> {
        if (!valid(registration) || !current(registration.authority)) {
            return NotificationsResult.PermissionDenied
        }
        val lease = sessions.currentAccess() ?: return NotificationsResult.PermissionDenied
        if (lease.epoch != registration.authority.authorityEpoch) {
            return NotificationsResult.PermissionDenied
        }
        val payload = buildJsonObject {
            put("installationId", registration.installationId)
            put("platform", registration.platform)
            put("providerToken", registration.providerToken)
        }.toString()
        val result = try {
            calls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = PUSH_SUBSCRIPTIONS_PATH,
                    payload = payload,
                    idempotencyKey = registration.idempotencyKey,
                    nativeClient = true
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return NotificationsResult.Unavailable
        }
        if (!sessions.isEpochCurrent(lease.epoch) || !current(registration.authority)) {
            return NotificationsResult.PermissionDenied
        }
        return when (result) {
            is ProtectedResult.Success -> if (enabledForCurrentRegistration(
                    result.body,
                    registration
                )
            ) {
                NotificationsResult.Success(Unit)
            } else {
                NotificationsResult.Unavailable
            }

            is ProtectedResult.Failure -> when (result.error.kind) {
                FailureKind.AuthenticationRequired,
                FailureKind.AuthorizationFailure -> NotificationsResult.PermissionDenied

                FailureKind.BusinessConflict,
                FailureKind.StaleState,
                FailureKind.PreconditionRequired -> NotificationsResult.Conflict

                else -> NotificationsResult.Unavailable
            }
        }
    }

    private fun enabledForCurrentRegistration(
        body: String?,
        registration: PushSubscriptionRegistration
    ): Boolean = runCatching {
        val response = json.parseToJsonElement(body ?: "").jsonObject
        UUID.fromString(response.getValue("id").jsonPrimitive.content)
        response.getValue("status").jsonPrimitive.content == ENABLED_STATUS &&
            response.getValue("installationId").jsonPrimitive.content ==
            registration.installationId &&
            response.getValue("platform").jsonPrimitive.content == registration.platform &&
            response.getValue("surface").jsonPrimitive.content == OPERATIONS_SURFACE &&
            response.getValue("recipientMembershipId").jsonPrimitive.content ==
            registration.authority.membershipId
    }.getOrDefault(false)

    private fun valid(registration: PushSubscriptionRegistration): Boolean =
        safeFid(registration) &&
            registration.authority.authorityEpoch > 0 &&
            READ_PERMISSION in registration.authority.permissions &&
            registration.installationId.isNotBlank() &&
            registration.installationId.length <= 160 &&
            registration.platform == ANDROID_PLATFORM &&
            registration.providerToken.isNotBlank() &&
            registration.providerToken.length <= 4096 &&
            '\r' !in registration.providerToken && '\n' !in registration.providerToken &&
            registration.idempotencyKey.isNotBlank() &&
            registration.idempotencyKey.length <= 160

    private fun safeFid(registration: PushSubscriptionRegistration): Boolean {
        val fid = registration.providerToken.removePrefix(PROVIDER_TOKEN_PREFIX)
        return registration.providerToken.startsWith(PROVIDER_TOKEN_PREFIX) &&
            FID_PATTERN.matches(fid) &&
            registration.installationId == "$INSTALLATION_PREFIX${fingerprint(fid)}"
    }

    private fun fingerprint(value: String): String = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun current(authority: NotificationsAuthority): Boolean {
        if (authority.authorityEpoch <= 0 || READ_PERMISSION !in authority.permissions) {
            return false
        }
        val verified = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active &&
            verified.hasAuthorizedContext &&
            verified.userId == authority.userId &&
            verified.tenantId == authority.tenantId &&
            verified.workspaceId == authority.workspaceId &&
            verified.membershipId == authority.membershipId &&
            verified.permissions == authority.permissions
    }

    private companion object {
        const val PUSH_SUBSCRIPTIONS_PATH = "/api/v1/notifications/push-subscriptions"
        const val READ_PERMISSION = "notification.read"
        const val ANDROID_PLATFORM = "ANDROID"
        const val OPERATIONS_SURFACE = "PLATFORM"
        const val ENABLED_STATUS = "ENABLED"
        const val INSTALLATION_PREFIX = "operations-"
        const val PROVIDER_TOKEN_PREFIX = "fcm-fid:"
        val FID_PATTERN = Regex("[A-Za-z0-9_-]{1,149}")
        val json = Json { ignoreUnknownKeys = true }
    }
}
