package com.nexa.mobile.operations.feature.delivery

import com.nexa.mobile.operations.feature.delivery.application.DriverHandoffTokenGateway
import com.nexa.mobile.operations.feature.delivery.application.DriverHandoffTokenMetadataStore
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffCurrentDelivery
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffCurrentDeliveryResult
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffIssueCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffIssueResult
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverHandoffTokenReceipt
import com.nexa.mobile.operations.feature.delivery.model.driverHandoffIssueBody
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriverHandoffTokenViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun recoveredCommandRequiresExplicitExactReplayAndNullTokenStaysUnavailable() = runTest {
        val store = FakeMetadataStore()
        val frozen = command()
        store.commands += frozen
        val gateway = FakeGateway().apply {
            issueResult = DriverHandoffIssueResult.TokenUnavailable(receipt())
        }
        val viewModel =
            DriverHandoffTokenViewModel(gateway, store, keyFactory = { "must-not-be-used" })
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
        val viewModel = DriverHandoffTokenViewModel(gateway, store, keyFactory = { "stable-key" })
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
        val viewModel = DriverHandoffTokenViewModel(gateway, store)
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
        val viewModel = DriverHandoffTokenViewModel(gateway, store, keyFactory = { "stable-key" })
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
        var issueResult: DriverHandoffIssueResult = DriverHandoffIssueResult.UnknownOutcome
        var pendingIssue: CompletableDeferred<DriverHandoffIssueResult>? = null
        val issued = mutableListOf<DriverHandoffIssueCommand>()

        override suspend fun currentDelivery(
            deliveryId: String,
            authority: DriverDeliveryAuthority
        ): DriverHandoffCurrentDeliveryResult = DriverHandoffCurrentDeliveryResult.Loaded(
            DriverHandoffCurrentDelivery(deliveryId, "IN_TRANSIT", 7, activeAttemptId)
        )

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

        override suspend fun clearKnownRejection(
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
            driverHandoffIssueBody(ATTEMPT_ID)
        )

        fun receipt() = DriverHandoffTokenReceipt(
            HANDOFF_ID,
            DELIVERY_ID,
            ATTEMPT_ID,
            Instant.now().plusSeconds(120).toString(),
            "ACTIVE"
        )
    }
}
