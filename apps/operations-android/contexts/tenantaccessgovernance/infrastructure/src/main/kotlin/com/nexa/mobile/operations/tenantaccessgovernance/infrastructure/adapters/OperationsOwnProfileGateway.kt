package com.nexa.mobile.operations.tenantaccessgovernance.infrastructure.adapters

import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.profile.OwnProfileGateway
import com.nexa.mobile.operations.tenantaccessgovernance.application.profile.OwnProfileResult
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.profile.OwnProfile
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.profile.OwnProfileEdit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

class OperationsOwnProfileGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val calls: ProtectedCallExecutor
) : OwnProfileGateway {
    override suspend fun load(authority: VerifiedContextAuthority): OwnProfileResult =
        execute(authority, ProtectedRequest(ProtectedMethod.GET, "/api/v1/me/profile"))

    override suspend fun update(
        authority: VerifiedContextAuthority,
        current: OwnProfile,
        edit: OwnProfileEdit
    ): OwnProfileResult {
        if (current.userId != authority.userId ||
            current.version < 0
        ) {
            return OwnProfileResult.Denied
        }
        val payload = buildJsonObject {
            put("displayName", edit.displayName.trim())
            put(
                "firstName",
                edit.firstName.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull
            )
            put(
                "lastName",
                edit.lastName.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull
            )
            put(
                "phone",
                edit.phone.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull
            )
            put("preferredLanguage", edit.preferredLanguage)
            put("timezone", current.timezone)
        }.toString()
        val result = execute(
            authority,
            ProtectedRequest(
                ProtectedMethod.PATCH,
                "/api/v1/me/profile",
                payload,
                ifMatch = "\"${current.version}\""
            )
        )
        return if (result is OwnProfileResult.Available &&
            result.profile.version <= current.version
        ) {
            OwnProfileResult.Unavailable
        } else {
            result
        }
    }

    private suspend fun execute(
        authority: VerifiedContextAuthority,
        request: ProtectedRequest
    ): OwnProfileResult {
        if (!current(authority)) return OwnProfileResult.Denied
        val lease = sessions.currentAccess() ?: return OwnProfileResult.Denied
        val result = try {
            calls.execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return OwnProfileResult.Unavailable
        }
        if (!sessions.isEpochCurrent(lease.epoch) ||
            !current(authority)
        ) {
            return OwnProfileResult.Denied
        }
        return when (result) {
            is ProtectedResult.Success -> runCatching {
                val body = Json.parseToJsonElement(result.body ?: "").jsonObject
                fun text(key: String) =
                    body.getValue(key).jsonPrimitive.content.also { require(it.isNotBlank()) }
                fun optional(key: String) = body[key]?.jsonPrimitive?.contentOrNull
                OwnProfile(
                    text("userId"), text("email"), text("displayName"), optional("firstName"),
                    optional(
                        "lastName"
                    ),
                    optional("phone"), text("preferredLanguage"), text("timezone"),
                    body.getValue("version").jsonPrimitive.long
                ).also {
                    require(it.userId == authority.userId && it.version >= 0)
                }
            }.getOrNull()?.let(OwnProfileResult::Available) ?: OwnProfileResult.Unavailable

            is ProtectedResult.Failure -> when (result.error.kind) {
                FailureKind.AuthenticationRequired,
                FailureKind.AuthorizationFailure -> OwnProfileResult.Denied

                FailureKind.ValidationFailure -> OwnProfileResult.Invalid

                FailureKind.BusinessConflict,
                FailureKind.StaleState,
                FailureKind.PreconditionRequired -> OwnProfileResult.Conflict

                else -> OwnProfileResult.Unavailable
            }
        }
    }
    private fun current(authority: VerifiedContextAuthority): Boolean {
        val session = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active && session.hasAuthorizedContext &&
            session.userId == authority.userId && session.tenantId == authority.tenantId &&
            session.workspaceId == authority.workspaceId &&
            session.membershipId == authority.membershipId &&
            session.permissions == authority.permissions
    }
}
