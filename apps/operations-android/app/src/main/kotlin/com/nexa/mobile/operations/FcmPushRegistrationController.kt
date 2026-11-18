package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import java.security.MessageDigest

private const val READ_PERMISSION = "notification.read"

internal data class FcmPushScope(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "FcmPushScope(REDACTED)"

    companion object {
        fun from(session: VerifiedSession): FcmPushScope? {
            if (!session.hasAuthorizedContext || READ_PERMISSION !in session.permissions) {
                return null
            }
            val userId = session.userId?.takeIf(String::isNotBlank) ?: return null
            val tenantId = session.tenantId?.takeIf(String::isNotBlank) ?: return null
            val workspaceId = session.workspaceId?.takeIf(String::isNotBlank) ?: return null
            val membershipId = session.membershipId?.takeIf(String::isNotBlank) ?: return null
            return FcmPushScope(userId, tenantId, workspaceId, membershipId)
        }
    }
}

internal data class FcmPushRegistrationRequest(
    val scope: FcmPushScope,
    val installationId: String,
    val providerToken: String,
    val firebaseInstallationId: String,
    val idempotencyKey: String
) {
    override fun toString(): String = "FcmPushRegistrationRequest(REDACTED)"
}

internal sealed interface FcmPushRegistrationDecision {
    data object Keep : FcmPushRegistrationDecision

    data object Reset : FcmPushRegistrationDecision

    data object ResetAndRegister : FcmPushRegistrationDecision

    data object StartRegistration : FcmPushRegistrationDecision

    data class Register(val request: FcmPushRegistrationRequest) :
        FcmPushRegistrationDecision {
        override fun toString(): String = "Register(REDACTED)"
    }
}

internal class FcmPushRegistrationController {
    private var currentScope: FcmPushScope? = null
    private var currentInstallationFingerprint: String? = null
    private var successfulCommandFingerprint: String? = null

    fun decide(
        sessionState: SessionState,
        verifiedSession: VerifiedSession?,
        notificationsAllowed: Boolean,
        firebaseInstallationId: String?
    ): FcmPushRegistrationDecision = when (sessionState) {
        SessionState.Bootstrapping,
        is SessionState.Restoring -> FcmPushRegistrationDecision.Keep

        SessionState.Active -> activeDecision(
            verifiedSession,
            notificationsAllowed,
            firebaseInstallationId
        )

        SessionState.SignedOut,
        SessionState.ContextRequired,
        SessionState.ReauthenticationRequired,
        SessionState.LocalProtectionError -> reset(firebaseInstallationId)
    }

    fun recordResult(request: FcmPushRegistrationRequest, succeeded: Boolean) {
        if (currentScope != request.scope ||
            currentInstallationFingerprint != fingerprint(request.firebaseInstallationId)
        ) {
            return
        }
        successfulCommandFingerprint = if (succeeded) {
            fingerprint(request.idempotencyKey)
        } else {
            null
        }
    }

    fun forgetInstallation(firebaseInstallationId: String) {
        if (currentInstallationFingerprint == fingerprint(firebaseInstallationId)) {
            currentInstallationFingerprint = null
            successfulCommandFingerprint = null
        }
    }

    fun forgetInstallationFromReset() {
        currentInstallationFingerprint = null
        successfulCommandFingerprint = null
    }

    private fun activeDecision(
        verifiedSession: VerifiedSession?,
        notificationsAllowed: Boolean,
        firebaseInstallationId: String?
    ): FcmPushRegistrationDecision {
        if (!notificationsAllowed) return reset(firebaseInstallationId)
        val session = verifiedSession ?: return FcmPushRegistrationDecision.Keep
        val scope = FcmPushScope.from(session) ?: return reset(firebaseInstallationId)
        if (currentScope != null && currentScope != scope) {
            currentScope = scope
            currentInstallationFingerprint = null
            successfulCommandFingerprint = null
            return FcmPushRegistrationDecision.ResetAndRegister
        }
        currentScope = scope
        val fid = firebaseInstallationId?.takeIf(String::isNotBlank)
            ?: return if (currentInstallationFingerprint == null) {
                FcmPushRegistrationDecision.StartRegistration
            } else {
                FcmPushRegistrationDecision.Keep
            }
        if (!safeFirebaseInstallationId.matches(fid)) {
            return FcmPushRegistrationDecision.Keep
        }
        val installationId = "operations-${fingerprint(fid)}"
        val providerToken = "fcm-fid:$fid"
        if (installationId.length > MAX_INSTALLATION_ID_LENGTH ||
            providerToken.length > MAX_PROVIDER_TOKEN_LENGTH ||
            fid.any(Char::isISOControl)
        ) {
            return FcmPushRegistrationDecision.Keep
        }
        val installationFingerprint = fingerprint(fid)
        if (currentInstallationFingerprint != installationFingerprint) {
            currentInstallationFingerprint = installationFingerprint
            successfulCommandFingerprint = null
        }
        val idempotencyKey = idempotencyKey(scope, installationId, providerToken)
        if (successfulCommandFingerprint == fingerprint(idempotencyKey)) {
            return FcmPushRegistrationDecision.Keep
        }
        return FcmPushRegistrationDecision.Register(
            FcmPushRegistrationRequest(
                scope = scope,
                installationId = installationId,
                providerToken = providerToken,
                firebaseInstallationId = fid,
                idempotencyKey = idempotencyKey
            )
        )
    }

    private fun reset(firebaseInstallationId: String?): FcmPushRegistrationDecision {
        val shouldReset = currentScope != null || currentInstallationFingerprint != null ||
            !firebaseInstallationId.isNullOrBlank()
        currentScope = null
        currentInstallationFingerprint = null
        successfulCommandFingerprint = null
        return if (shouldReset) {
            FcmPushRegistrationDecision.Reset
        } else {
            FcmPushRegistrationDecision.Keep
        }
    }

    private fun idempotencyKey(
        scope: FcmPushScope,
        installationId: String,
        providerToken: String
    ): String = "pushreg-v1-${fingerprint(
        listOf(
            scope.userId,
            scope.tenantId,
            scope.workspaceId,
            scope.membershipId,
            installationId,
            ANDROID_PLATFORM,
            providerToken
        ).joinToString("|")
    )}"

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val ANDROID_PLATFORM = "ANDROID"
        const val MAX_INSTALLATION_ID_LENGTH = 160
        const val MAX_PROVIDER_TOKEN_LENGTH = 4096
        val safeFirebaseInstallationId = Regex("[A-Za-z0-9_-]{1,149}")
    }
}
