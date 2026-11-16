package com.nexa.mobile.operations

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.notifications.application.inbox.NotificationsAuthority
import com.nexa.mobile.operations.notifications.application.inbox.NotificationsResult
import com.nexa.mobile.operations.notifications.application.push.PushSubscriptionRegistration
import com.nexa.mobile.operations.notifications.application.push.PushSubscriptionRegistrationGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class FcmPushRegistrationCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionCoordinator,
    private val registrations: PushSubscriptionRegistrationGateway,
    private val applicationScope: CoroutineScope
) {
    private val controller = FcmPushRegistrationController()
    private val installationState = FcmPushInstallationStateStore(context)
    private val notificationPermission = MutableStateFlow(
        FirebaseMessagingRuntime.notificationsAllowed(context)
    )
    private val mutex = Mutex()
    private val retiredInstallationFingerprints = LinkedHashSet<String>()
    private var currentFirebaseInstallationId: String? = null
    private var firebaseResetRequired = false
    private var terminalCleanupAttempted = false
    private var started = false

    fun start() {
        if (!BuildConfig.FIREBASE_MESSAGING_CONFIGURED || started) return
        started = true
        try {
            FirebaseMessaging.getInstance().setAutoInitEnabled(false)
        } catch (_: IllegalStateException) {
            return
        }
        applicationScope.launch {
            combine(
                sessions.sessionState,
                sessions.verifiedSession,
                notificationPermission
            ) { state, verified, allowed -> Snapshot(state, verified, allowed) }
                .collect { snapshot ->
                    mutex.withLock { reconcile(snapshot) }
                }
        }
    }

    fun onForeground() {
        if (!started) return
        notificationPermission.value = FirebaseMessagingRuntime.notificationsAllowed(context)
        applicationScope.launch {
            mutex.withLock {
                reconcile(currentSnapshot())
            }
        }
    }

    fun onFirebaseRegistered(installationId: String) {
        if (!started || installationId.isBlank() || installationId.length > 160) return
        applicationScope.launch {
            mutex.withLock {
                val fingerprint = fingerprint(installationId)
                if (fingerprint in retiredInstallationFingerprints) return@withLock
                currentFirebaseInstallationId = installationId
                reconcile(currentSnapshot())
            }
        }
    }

    fun onFirebaseUnregistered(installationId: String) {
        if (!started || installationId.isBlank()) return
        applicationScope.launch {
            mutex.withLock {
                if (currentFirebaseInstallationId == installationId) {
                    currentFirebaseInstallationId = null
                }
                controller.forgetInstallation(installationId)
            }
        }
    }

    private suspend fun reconcile(snapshot: Snapshot) {
        when (snapshot.sessionState) {
            SessionState.Bootstrapping,
            is SessionState.Restoring -> return

            SessionState.SignedOut,
            SessionState.ContextRequired,
            SessionState.ReauthenticationRequired,
            SessionState.LocalProtectionError -> {
                controller.decide(
                    snapshot.sessionState,
                    snapshot.verifiedSession,
                    snapshot.notificationsAllowed,
                    currentFirebaseInstallationId
                )
                if (!terminalCleanupAttempted || currentFirebaseInstallationId != null ||
                    firebaseResetRequired
                ) {
                    if (!installationState.markResetRequired()) {
                        firebaseResetRequired = true
                        terminalCleanupAttempted = false
                        return
                    }
                    val reset = resetFirebaseInstallation()
                    terminalCleanupAttempted = reset &&
                        installationState.clearScopeAndKeepResetRequired()
                    firebaseResetRequired = !terminalCleanupAttempted
                }
                return
            }

            SessionState.Active -> terminalCleanupAttempted = false
        }

        val scope = authorizedScope(snapshot)
        if (scope == null) {
            val decision = controller.decide(
                snapshot.sessionState,
                snapshot.verifiedSession,
                snapshot.notificationsAllowed,
                currentFirebaseInstallationId
            )
            val mustReset = decision == FcmPushRegistrationDecision.Reset ||
                currentFirebaseInstallationId != null ||
                installationState.scopeFingerprint() != null || firebaseResetRequired
            if (mustReset) {
                if (!installationState.markResetRequired()) {
                    firebaseResetRequired = true
                    return
                }
                val reset = resetFirebaseInstallation()
                if (reset) {
                    firebaseResetRequired = !installationState.clearScopeAndKeepResetRequired()
                } else {
                    firebaseResetRequired = true
                }
            }
            return
        }

        val scopeFingerprint = scopeFingerprint(scope)
        if (firebaseResetRequired || installationState.resetRequired() ||
            installationState.scopeFingerprint() != scopeFingerprint
        ) {
            controller.decide(
                snapshot.sessionState,
                snapshot.verifiedSession,
                snapshot.notificationsAllowed,
                currentFirebaseInstallationId
            )
            if (!installationState.markResetRequired()) {
                firebaseResetRequired = true
                return
            }
            if (!resetFirebaseInstallation()) return
            if (!installationState.recordScopeAfterReset(scopeFingerprint)) {
                firebaseResetRequired = true
                return
            }
            firebaseResetRequired = false
            startFirebaseRegistration()
            return
        }

        val decision = controller.decide(
            snapshot.sessionState,
            snapshot.verifiedSession,
            snapshot.notificationsAllowed,
            currentFirebaseInstallationId
        )
        apply(decision)
    }

    private suspend fun apply(decision: FcmPushRegistrationDecision) {
        when (decision) {
            FcmPushRegistrationDecision.Keep -> Unit

            FcmPushRegistrationDecision.Reset -> {
                if (!installationState.markResetRequired() ||
                    !resetFirebaseInstallation() ||
                    !installationState.clearScopeAndKeepResetRequired()
                ) {
                    firebaseResetRequired = true
                }
            }

            FcmPushRegistrationDecision.ResetAndRegister -> {
                val scope = authorizedScope(currentSnapshot())
                if (scope != null && installationState.markResetRequired() &&
                    resetFirebaseInstallation() && installationState.recordScopeAfterReset(
                        scopeFingerprint(scope)
                    )
                ) {
                    startFirebaseRegistration()
                } else {
                    firebaseResetRequired = true
                }
            }

            FcmPushRegistrationDecision.StartRegistration -> startFirebaseRegistration()

            is FcmPushRegistrationDecision.Register -> registerWithApi(decision.request)
        }
    }

    private suspend fun registerWithApi(request: FcmPushRegistrationRequest) {
        val authority = authorityFor(request.scope) ?: run {
            controller.recordResult(request, succeeded = false)
            return
        }
        val result = try {
            registrations.register(
                PushSubscriptionRegistration(
                    authority = authority,
                    installationId = request.installationId,
                    platform = ANDROID_PLATFORM,
                    providerToken = request.providerToken,
                    idempotencyKey = request.idempotencyKey
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NotificationsResult.Unavailable
        }
        controller.recordResult(
            request,
            succeeded = result is NotificationsResult.Success
        )
        if (result is NotificationsResult.Success) {
            installationState.recordRegisteredScope(scopeFingerprint(request.scope))
        }
    }

    private suspend fun resetFirebaseInstallation(): Boolean {
        currentFirebaseInstallationId?.let { retiredInstallationFingerprints += fingerprint(it) }
        while (retiredInstallationFingerprints.size > MAX_RETIRED_INSTALLATIONS) {
            retiredInstallationFingerprints.remove(retiredInstallationFingerprints.first())
        }
        val messaging = try {
            FirebaseMessaging.getInstance().also { it.setAutoInitEnabled(false) }
        } catch (_: IllegalStateException) {
            firebaseResetRequired = true
            return false
        }
        var succeeded = true
        try {
            messaging.unregister().awaitTask()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            succeeded = false
        }
        try {
            FirebaseInstallations.getInstance().delete().awaitTask()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            succeeded = false
        }
        if (succeeded) {
            currentFirebaseInstallationId = null
            controller.forgetInstallationFromReset()
            firebaseResetRequired = false
        } else {
            firebaseResetRequired = true
        }
        return succeeded
    }

    private suspend fun startFirebaseRegistration() {
        if (authorizedScope(currentSnapshot()) == null) return
        try {
            FirebaseMessaging.getInstance().also { it.setAutoInitEnabled(true) }
                .register()
                .awaitTask()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Foreground reconciliation retries the idempotent FCM registration request.
        }
    }

    private suspend fun authorityFor(scope: FcmPushScope): NotificationsAuthority? {
        if (authorizedScope(currentSnapshot()) != scope) return null
        val verified = sessions.verifiedSession.value ?: return null
        val lease = sessions.currentAccess() ?: return null
        if (authorizedScope(currentSnapshot()) != scope) return null
        return NotificationsAuthority(
            userId = verified.userId ?: return null,
            tenantId = verified.tenantId ?: return null,
            workspaceId = verified.workspaceId ?: return null,
            membershipId = verified.membershipId ?: return null,
            permissions = verified.permissions,
            authorityEpoch = lease.epoch
        )
    }

    private fun authorizedScope(snapshot: Snapshot): FcmPushScope? {
        if (snapshot.sessionState != SessionState.Active || !snapshot.notificationsAllowed) {
            return null
        }
        return snapshot.verifiedSession?.let(FcmPushScope::from)
    }

    private fun scopeFingerprint(scope: FcmPushScope): String = fingerprint(
        listOf(scope.userId, scope.tenantId, scope.workspaceId, scope.membershipId)
            .joinToString("|")
    )

    private fun currentSnapshot() = Snapshot(
        sessionState = sessions.sessionState.value,
        verifiedSession = sessions.verifiedSession.value,
        notificationsAllowed = notificationPermission.value
    )

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (task.isSuccessful) {
                continuation.resume(task.result)
            } else {
                continuation.resumeWithException(
                    task.exception ?: IllegalStateException("Firebase task failed")
                )
            }
        }
    }

    private fun fingerprint(value: String): String = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Snapshot(
        val sessionState: SessionState,
        val verifiedSession: VerifiedSession?,
        val notificationsAllowed: Boolean
    )

    private companion object {
        const val ANDROID_PLATFORM = "ANDROID"
        const val MAX_RETIRED_INSTALLATIONS = 16
    }
}
