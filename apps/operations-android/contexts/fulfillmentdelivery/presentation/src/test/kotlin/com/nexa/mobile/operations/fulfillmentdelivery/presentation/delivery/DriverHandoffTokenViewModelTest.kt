package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffCurrentDeliveryResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffIssueCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffIssueResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffTokenGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffTokenMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryAttempt
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverHandoffCurrentDelivery
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverHandoffTokenReceipt
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.testsupport.TestDeliveryRequestBodyCodec
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriverHandoffTokenViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun pendingIssueCanRetrySameAfterCurrentDeliveryReadRecovers() = runTest {
        val store = FakeMetadataStore()
        val frozen = command()
        store.commands += frozen
        val gateway = FakeGateway()
        val viewModel = DriverHandoffTokenViewModel(
            gateway,
            store,
            keyFactory = { error("A recovered command must retain its key") },
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()

        gateway.currentAvailable = false
        viewModel.issueOrRetrySame()
        advanceUntilIdle()
        assertEquals(DriverHandoffUiStatus.Unavailable, viewModel.state.value.status)
        assertTrue(gateway.issued.isEmpty())
        assertEquals(frozen, store.commands.single())
        assertTrue(viewModel.state.value.canRetrySame)

        gateway.currentAvailable = true
        viewModel.issueOrRetrySame()
        advanceUntilIdle()
        assertEquals(listOf(frozen), gateway.issued)
        assertEquals(frozen, store.commands.single())
    }

    @Test
    fun assignedDeliveredAndPartialAttemptsCanIssueAfterProcessRestart() = runTest {
        for ((deliveryStatus, attemptStatus, outcome) in listOf(
            Triple("DELIVERED", "FINAL", "DELIVERED"),
            Triple("PARTIAL", "PARTIAL", "PARTIAL"),
            Triple("DELIVERED", "PARTIAL", "PARTIAL")
        )) {
            val gateway = FakeGateway().apply {
                currentDeliveryValue = terminalDelivery(
                    deliveryStatus,
                    attemptStatus,
                    outcome
                )
                issueResult = DriverHandoffIssueResult.Issued(receipt(), "terminal-code")
            }
            val viewModel = DriverHandoffTokenViewModel(
                gateway,
                FakeMetadataStore(),
                keyFactory = { "terminal-$outcome-key" },
                requestBodyCodec = TestDeliveryRequestBodyCodec()
            )
            viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.canIssue)
            viewModel.issueOrRetrySame()
            runCurrent()

            assertEquals(DriverHandoffUiStatus.TokenVisible, viewModel.state.value.status)
            assertEquals(ATTEMPT_ID, gateway.issued.single().attemptId)
            assertEquals(8L, gateway.issued.single().expectedVersion)
        }
    }

    @Test
    fun terminalFailedStaleAndUnknownAttemptsCannotIssue() = runTest {
        val invalidTerminalCases = listOf(
            terminalDelivery("DELIVERED", "FAILED", "FAILED", eligible = false),
            terminalDelivery(
                "DELIVERED",
                "FINAL",
                "DELIVERED",
                attemptId = OTHER_ATTEMPT_ID
            ),
            terminalDelivery("DELIVERED", "FINAL", "UNKNOWN"),
            terminalDelivery("DELIVERED", "FINAL", "PARTIAL"),
            terminalDelivery("PARTIAL", "FINAL", "PARTIAL"),
            terminalDelivery("DELIVERED", "PARTIAL", "DELIVERED"),
            terminalDelivery(
                "DELIVERED",
                "FINAL",
                "DELIVERED",
                startedByMembershipId = OTHER_MEMBERSHIP_ID
            )
        )
        for (current in invalidTerminalCases) {
            val gateway = FakeGateway().apply { currentDeliveryValue = current }
            val viewModel = DriverHandoffTokenViewModel(
                gateway,
                FakeMetadataStore(),
                requestBodyCodec = TestDeliveryRequestBodyCodec()
            )
            viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.canIssue)
            viewModel.issueOrRetrySame()
            advanceUntilIdle()
            assertTrue(gateway.issued.isEmpty())
        }
    }

    @Test
    fun scopeDriftBlocksTerminalIssueBeforePosting() = runTest {
        val gateway = FakeGateway().apply {
            currentFailure = DriverHandoffCurrentDeliveryResult.ContextInvalidated
        }
        val viewModel = DriverHandoffTokenViewModel(
            gateway,
            FakeMetadataStore(),
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()

        assertEquals(DriverHandoffUiStatus.Unavailable, viewModel.state.value.status)
        viewModel.issueOrRetrySame()
        advanceUntilIdle()
        assertTrue(gateway.issued.isEmpty())
    }

    @Test
    fun confirmedTerminalIssuanceCanRenewAfterFreshEligibleRead() = runTest {
        val frozen = command()
        val store = FakeMetadataStore().apply { commands += frozen }
        val gateway = FakeGateway().apply {
            currentDeliveryValue = terminalDelivery("DELIVERED", "FINAL", "DELIVERED")
            issueResult = DriverHandoffIssueResult.TokenUnavailable(receipt())
        }
        val viewModel = DriverHandoffTokenViewModel(
            gateway,
            store,
            keyFactory = { "terminal-renewal-key" },
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()
        viewModel.issueOrRetrySame()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.canRenew)

        viewModel.renewConfirmedToken()
        advanceUntilIdle()

        assertEquals(2, gateway.issued.size)
        assertEquals("terminal-renewal-key", gateway.issued.last().idempotencyKey)
        assertEquals(gateway.issued.last(), store.commands.single())
    }

    @Test
    fun renewalNeedsConfirmedIssuanceAndPreservesUnknownCommand() = runTest {
        val store = FakeMetadataStore().apply { commands += command() }
        val gateway = FakeGateway()
        val viewModel = DriverHandoffTokenViewModel(
            gateway,
            store,
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()
        viewModel.renewConfirmedToken()
        advanceUntilIdle()
        assertTrue(gateway.issued.isEmpty())
        assertEquals(command(), store.commands.single())
    }

    @Test
    fun explicitRenewalUsesNewKeyOnlyAfterConfirmedIssueAndFreshAttempt() = runTest {
        val frozen = command()
        val store = FakeMetadataStore().apply { commands += frozen }
        val gateway = FakeGateway().apply {
            issueResult = DriverHandoffIssueResult.TokenUnavailable(receipt())
        }
        val viewModel = DriverHandoffTokenViewModel(
            gateway,
            store,
            keyFactory = { "new-explicit-key" },
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()
        viewModel.issueOrRetrySame()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.canRenew)

        viewModel.renewConfirmedToken()
        advanceUntilIdle()
        assertEquals(2, gateway.issued.size)
        assertEquals(frozen, gateway.issued.first())
        assertEquals("new-explicit-key", gateway.issued.last().idempotencyKey)
        assertEquals(gateway.issued.last(), store.commands.single())
    }

    @Test
    fun changedAttemptBlocksRenewalAndRetainsConfirmedCommand() = runTest {
        val frozen = command()
        val store = FakeMetadataStore().apply { commands += frozen }
        val gateway = FakeGateway().apply {
            issueResult = DriverHandoffIssueResult.TokenUnavailable(receipt())
        }
        val viewModel = DriverHandoffTokenViewModel(
            gateway,
            store,
            requestBodyCodec = TestDeliveryRequestBodyCodec()
        )
        viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()
        viewModel.issueOrRetrySame()
        advanceUntilIdle()
        gateway.activeAttemptId = OTHER_ATTEMPT_ID
        viewModel.renewConfirmedToken()
        advanceUntilIdle()
        assertEquals(listOf(frozen), gateway.issued)
        assertEquals(frozen, store.commands.single())
        assertEquals(DriverHandoffUiStatus.Stale, viewModel.state.value.status)
    }

    @Test
    fun recoveredCommandRequiresExplicitExactReplayAndNullTokenStaysUnavailable() = runTest {
        val store = FakeMetadataStore()
        val frozen = command()
        store.commands += frozen
        val gateway = FakeGateway().apply {
            issueResult = DriverHandoffIssueResult.TokenUnavailable(receipt())
        }
        val viewModel =
            DriverHandoffTokenViewModel(gateway, store, keyFactory = {
                "must-not-be-used"
            }, requestBodyCodec = TestDeliveryRequestBodyCodec())
        viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()

        assertEquals(DriverHandoffUiStatus.UnknownOutcome, viewModel.state.value.status)
        assertTrue(gateway.issued.isEmpty())
        viewModel.issueOrRetrySame()
        advanceUntilIdle()

        assertEquals(listOf(frozen), gateway.issued)
        assertEquals(DriverHandoffUiStatus.TokenUnavailable, viewModel.state.value.status)
        assertNull(viewModel.state.value.token)
        assertEquals(frozen, store.commands.single())
    }

    @Test
    fun freshIssuePersistsBeforePostAndBackgroundClearErasesOnlyInMemoryToken() = runTest {
        val events = mutableListOf<String>()
        val store = FakeMetadataStore(events)
        val gateway = FakeGateway(events).apply {
            issueResult = DriverHandoffIssueResult.Issued(receipt(), "123456")
        }
        val viewModel =
            DriverHandoffTokenViewModel(gateway, store, keyFactory = {
                "stable-key"
            }, requestBodyCodec = TestDeliveryRequestBodyCodec())
        viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()
        viewModel.issueOrRetrySame()
        runCurrent()

        assertEquals(listOf("persist", "post"), events)
        assertEquals("123456", viewModel.state.value.token)
        assertEquals("stable-key", store.commands.single().idempotencyKey)

        viewModel.clearToken()
        assertEquals(DriverHandoffUiStatus.Cleared, viewModel.state.value.status)
        assertNull(viewModel.state.value.token)
        assertEquals(1, store.commands.size)
    }

    @Test
    fun changedActiveAttemptCannotIssue() = runTest {
        val store = FakeMetadataStore()
        val gateway = FakeGateway().apply { activeAttemptId = OTHER_ATTEMPT_ID }
        val viewModel =
            DriverHandoffTokenViewModel(
                gateway,
                store,
                requestBodyCodec = TestDeliveryRequestBodyCodec()
            )
        viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()

        assertEquals(DriverHandoffUiStatus.Stale, viewModel.state.value.status)
        viewModel.issueOrRetrySame()
        advanceUntilIdle()
        assertTrue(gateway.issued.isEmpty())
        assertTrue(store.commands.isEmpty())
    }

    @Test
    fun backgroundWhileIssuePendingKeepsFrozenCommandAndDropsLateToken() = runTest {
        val store = FakeMetadataStore()
        val lateResponse = CompletableDeferred<DriverHandoffIssueResult>()
        val gateway = FakeGateway().apply { pendingIssue = lateResponse }
        val viewModel =
            DriverHandoffTokenViewModel(gateway, store, keyFactory = {
                "stable-key"
            }, requestBodyCodec = TestDeliveryRequestBodyCodec())
        viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()

        viewModel.issueOrRetrySame()
        runCurrent()
        val frozen = store.commands.single()
        assertTrue(viewModel.state.value.busy)

        viewModel.clearToken()
        assertEquals(DriverHandoffUiStatus.UnknownOutcome, viewModel.state.value.status)
        assertNull(viewModel.state.value.token)
        assertEquals(frozen, viewModel.state.value.command)

        lateResponse.complete(DriverHandoffIssueResult.Issued(receipt(), "683291"))
        runCurrent()

        assertEquals(DriverHandoffUiStatus.UnknownOutcome, viewModel.state.value.status)
        assertNull(viewModel.state.value.token)
        assertEquals(frozen, viewModel.state.value.command)
        assertEquals(listOf(frozen), store.commands)
        assertEquals(listOf(frozen), gateway.issued)
    }

    private class FakeGateway(private val events: MutableList<String> = mutableListOf()) :
        DriverHandoffTokenGateway {
        var activeAttemptId: String? = ATTEMPT_ID
        var currentAvailable = true
        var currentFailure: DriverHandoffCurrentDeliveryResult? = null
        var currentDeliveryValue: DriverHandoffCurrentDelivery? = null
        var issueResult: DriverHandoffIssueResult = DriverHandoffIssueResult.UnknownOutcome
        var pendingIssue: CompletableDeferred<DriverHandoffIssueResult>? = null
        val issued = mutableListOf<DriverHandoffIssueCommand>()

        override suspend fun currentDelivery(
            deliveryId: String,
            authority: DriverDeliveryAuthority
        ): DriverHandoffCurrentDeliveryResult = currentFailure ?: if (currentAvailable) {
            DriverHandoffCurrentDeliveryResult.Loaded(
                currentDeliveryValue ?: DriverHandoffCurrentDelivery(
                    deliveryId,
                    "IN_TRANSIT",
                    7,
                    activeAttemptId
                )
            )
        } else {
            DriverHandoffCurrentDeliveryResult.Unavailable
        }

        override suspend fun issue(
            command: DriverHandoffIssueCommand,
            authority: DriverDeliveryAuthority
        ): DriverHandoffIssueResult {
            events += "post"
            issued += command
            return pendingIssue?.await() ?: issueResult
        }
    }

    private class FakeMetadataStore(private val events: MutableList<String> = mutableListOf()) :
        DriverHandoffTokenMetadataStore {
        val commands = mutableListOf<DriverHandoffIssueCommand>()

        override suspend fun load(
            scope: DriverAttemptScopeIdentity,
            deliveryId: String,
            attemptId: String
        ): DriverHandoffMetadataRead = DriverHandoffMetadataRead.Available(
            commands.firstOrNull { it.deliveryId == deliveryId && it.attemptId == attemptId }
        )

        override suspend fun persistIntent(
            scope: DriverAttemptScopeIdentity,
            command: DriverHandoffIssueCommand
        ): DriverHandoffMetadataWrite {
            events += "persist"
            val current = commands.firstOrNull {
                it.deliveryId == command.deliveryId && it.attemptId == command.attemptId
            }
            if (current != null && current != command) return DriverHandoffMetadataWrite.Conflict
            if (current == null) commands += command
            return DriverHandoffMetadataWrite.Saved
        }

        override suspend fun clearConfirmedResult(
            scope: DriverAttemptScopeIdentity,
            deliveryId: String,
            attemptId: String,
            idempotencyKey: String
        ): DriverHandoffMetadataWrite {
            val current =
                commands.firstOrNull { it.deliveryId == deliveryId && it.attemptId == attemptId }
                    ?: return DriverHandoffMetadataWrite.Saved
            if (current.idempotencyKey != idempotencyKey) return DriverHandoffMetadataWrite.Stale
            commands.remove(current)
            return DriverHandoffMetadataWrite.Saved
        }
    }

    private companion object {
        const val USER_ID = "11111111-1111-4111-8111-111111111111"
        const val TENANT_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val MEMBERSHIP_ID = "44444444-4444-4444-8444-444444444444"
        const val DELIVERY_ID = "55555555-5555-4555-8555-555555555555"
        const val ATTEMPT_ID = "66666666-6666-4666-8666-666666666666"
        const val OTHER_ATTEMPT_ID = "77777777-7777-4777-8777-777777777777"
        const val OTHER_MEMBERSHIP_ID = "99999999-9999-4999-8999-999999999999"
        const val HANDOFF_ID = "88888888-8888-4888-8888-888888888888"
        val AUTHORITY = DriverDeliveryAuthority(
            USER_ID,
            TENANT_ID,
            WORKSPACE_ID,
            MEMBERSHIP_ID,
            setOf("dispatch.read", "dispatch.start_route", "logistics:write"),
            authorityEpoch = 12
        )

        fun command() = DriverHandoffIssueCommand(
            DELIVERY_ID,
            ATTEMPT_ID,
            7,
            "stable-key",
            TestDeliveryRequestBodyCodec().driverHandoffIssueBody(ATTEMPT_ID)
        )

        fun receipt() = DriverHandoffTokenReceipt(
            HANDOFF_ID,
            DELIVERY_ID,
            ATTEMPT_ID,
            Instant.now().plusSeconds(120).toString(),
            "ACTIVE"
        )

        fun terminalDelivery(
            deliveryStatus: String,
            attemptStatus: String,
            outcome: String?,
            attemptId: String = ATTEMPT_ID,
            eligible: Boolean = true,
            startedByMembershipId: String = AUTHORITY.membershipId
        ) = DriverHandoffCurrentDelivery(
            DELIVERY_ID,
            deliveryStatus,
            8,
            activeAttemptId = null,
            lastAttempt = DriverDeliveryAttempt(
                attemptId,
                2,
                attemptStatus,
                startedByMembershipId,
                null,
                outcome
            ),
            handoffRenewalEligible = eligible
        )
    }
}
