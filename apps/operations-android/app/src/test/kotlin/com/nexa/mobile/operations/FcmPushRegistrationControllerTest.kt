package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmPushRegistrationControllerTest {
    @Test
    fun pushTapRequiresSafeEventAndSystemMessageMarkers() {
        assertTrue(isSafePushEventId("event_01HZX9A2"))
        assertFalse(isSafePushEventId(null))
        assertFalse(isSafePushEventId("https://example.test/inbox"))
        assertFalse(isSafePushEventId("../other"))
        assertFalse(isSafePushEventId("event id"))

        assertTrue(
            isFcmNotificationTap(
                action = ANDROID_MAIN_ACTION,
                hasLauncherCategory = true,
                explicitPushMarker = false,
                messageId = "0:opaque-message-id%part",
                eventId = "event_01HZX9A2"
            )
        )
        assertFalse(
            isFcmNotificationTap(
                ANDROID_MAIN_ACTION,
                hasLauncherCategory = true,
                explicitPushMarker = false,
                messageId = null,
                eventId = "event_01HZX9A2"
            )
        )
        assertFalse(
            isFcmNotificationTap(
                ANDROID_MAIN_ACTION,
                hasLauncherCategory = false,
                explicitPushMarker = false,
                messageId = "message-id",
                eventId = "event_01HZX9A2"
            )
        )
        assertFalse(
            isFcmNotificationTap(
                "android.intent.action.VIEW",
                hasLauncherCategory = false,
                explicitPushMarker = false,
                messageId = "message-id",
                eventId = "event_01HZX9A2"
            )
        )
        assertTrue(
            isFcmNotificationTap(
                ACTION_OPEN_NOTIFICATIONS_FROM_PUSH,
                hasLauncherCategory = false,
                explicitPushMarker = true,
                messageId = null,
                eventId = "event_01HZX9A2"
            )
        )
        assertFalse(isSafeFcmMessageId("message\nid"))
    }

    @Test
    fun requiresActiveVerifiedReadPermissionAndSystemPermission() {
        val controller = FcmPushRegistrationController()
        val verified = verifiedSession()

        assertEquals(
            FcmPushRegistrationDecision.Reset,
            controller.decide(SessionState.SignedOut, verified, true, "fid-a")
        )
        assertEquals(
            FcmPushRegistrationDecision.Keep,
            controller.decide(
                SessionState.Active,
                verified.copy(permissions = emptySet()),
                true,
                null
            )
        )
        assertEquals(
            FcmPushRegistrationDecision.Keep,
            controller.decide(SessionState.Active, verified, false, null)
        )
        assertEquals(
            FcmPushRegistrationDecision.StartRegistration,
            controller.decide(SessionState.Active, verified, true, null)
        )
    }

    @Test
    fun uncertainRegistrationRetryUsesTheSameKeyAndNeverMarksFailureComplete() {
        val controller = FcmPushRegistrationController()

        val first = controller.decide(
            SessionState.Active,
            verifiedSession(),
            true,
            "fid-a"
        ) as FcmPushRegistrationDecision.Register
        controller.recordResult(first.request, succeeded = false)
        val retry = controller.decide(
            SessionState.Active,
            verifiedSession(),
            true,
            "fid-a"
        ) as FcmPushRegistrationDecision.Register

        assertEquals(first.request.idempotencyKey, retry.request.idempotencyKey)
        assertEquals(
            "operations-ef6c2b2e3871de0eb68704908cc63d7a17e323a18d7bb692369f8f8d5b450e52",
            retry.request.installationId
        )
        assertFalse(retry.request.installationId.contains("fid-a"))
        assertEquals("fcm-fid:fid-a", retry.request.providerToken)
        assertFalse(retry.request.toString().contains("fid-a"))
        controller.recordResult(retry.request, succeeded = true)
        assertEquals(
            FcmPushRegistrationDecision.Keep,
            controller.decide(SessionState.Active, verifiedSession(), true, "fid-a")
        )
    }

    @Test
    fun onlyFirebaseInstallationIdSafeCharactersAreWrapped() {
        val controller = FcmPushRegistrationController()
        listOf("bad fid", "fid:other", "../fid", "fid=value").forEach { invalidFid ->
            assertEquals(
                FcmPushRegistrationDecision.Keep,
                controller.decide(
                    SessionState.Active,
                    verifiedSession(),
                    true,
                    invalidFid
                )
            )
        }
        val validFid = controller.decide(
            SessionState.Active,
            verifiedSession(),
            true,
            "Ab_09-x"
        )
        assertTrue(validFid is FcmPushRegistrationDecision.Register)
    }

    @Test
    fun scopeChangeResetsInstallationBeforeRegisteringForNextAccount() {
        val controller = FcmPushRegistrationController()
        val original = verifiedSession()
        controller.decide(SessionState.Active, original, true, "fid-a")

        val changed = controller.decide(
            SessionState.Active,
            original.copy(userId = "user-b", membershipId = "membership-b"),
            true,
            "fid-a"
        )

        assertEquals(FcmPushRegistrationDecision.ResetAndRegister, changed)
        assertEquals(
            FcmPushRegistrationDecision.StartRegistration,
            controller.decide(
                SessionState.Active,
                original.copy(userId = "user-b", membershipId = "membership-b"),
                true,
                null
            )
        )
    }

    @Test
    fun signOutResetsInstallationAndDiscardsRegistrationState() {
        val controller = FcmPushRegistrationController()
        val verified = verifiedSession()
        val registration = controller.decide(
            SessionState.Active,
            verified,
            true,
            "fid-a"
        ) as FcmPushRegistrationDecision.Register
        controller.recordResult(registration.request, succeeded = true)

        assertEquals(
            FcmPushRegistrationDecision.Reset,
            controller.decide(SessionState.SignedOut, null, false, "fid-a")
        )
        assertEquals(
            FcmPushRegistrationDecision.Keep,
            controller.decide(SessionState.SignedOut, null, false, null)
        )
    }

    private fun verifiedSession() = VerifiedSession(
        hasAuthorizedContext = true,
        userId = "user-a",
        tenantId = "tenant-a",
        workspaceId = "workspace-a",
        membershipId = "membership-a",
        permissions = setOf("notification.read")
    )
}
