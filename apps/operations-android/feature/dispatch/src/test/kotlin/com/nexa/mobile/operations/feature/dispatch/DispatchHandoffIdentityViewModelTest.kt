package com.nexa.mobile.operations.feature.dispatch

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchHandoffIdentityViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun issuePersistsExactCommandBeforeNetworkAndKeepsIssuedTokenOnlyInMemory() = runTest {
        val store = FakeMetadataStore()
        val gateway = FakeGateway(store)
        val viewModel = DispatchHandoffIdentityViewModel(
            gateway,
            store,
            keyFactory = { "issue-key-1" },
            now = { Instant.parse("2026-10-01T09:00:00Z") }
        )
        viewModel.activate(context(), DELIVERY_ID, ASSIGNMENT_ID)
        runCurrent()

        viewModel.issue()
        runCurrent()

        assertEquals(listOf("persist:issue-key-1", "issue:issue-key-1"), gateway.events)
        assertEquals(DispatchHandoffIdentityStatus.TokenVisible, viewModel.state.value.status)
        assertEquals("dsp_one_time_secret", viewModel.state.value.oneTimeToken)
        assertNull(viewModel.state.value.command)
        assertTrue(store.serializedIntent.contains("issue-key-1"))
        assertFalse(store.serializedIntent.contains("dsp_one_time_secret"))
        assertFalse(viewModel.state.value.toString().contains("dsp_one_time_secret"))

        viewModel.hideToken()
        assertNull(viewModel.state.value.oneTimeToken)
        assertEquals(DispatchHandoffIdentityStatus.ReissueRequired, viewModel.state.value.status)
    }

    @Test
    fun restoredUnknownIntentDoesNotAutoPostAndRetryUsesSameFrozenKey() = runTest {
        val store = FakeMetadataStore()
        val existing = command("prior-key")
        store.commands += existing
        val gateway = FakeGateway(store).apply {
            issueResponses += DispatchHandoffIssueResult.AcceptedWithoutToken(identity())
            issueResponses += DispatchHandoffIssueResult.Issued(identity(), "dsp_new_token")
        }
        val keys = ArrayDeque(listOf("replacement-key"))
        val viewModel = DispatchHandoffIdentityViewModel(
            gateway,
            store,
            keyFactory = { keys.removeFirst() },
            now = { Instant.parse("2026-10-01T09:00:00Z") }
        )
        viewModel.activate(context(), DELIVERY_ID, ASSIGNMENT_ID)
        runCurrent()
        assertEquals(DispatchHandoffIdentityStatus.UnknownOutcome, viewModel.state.value.status)
        assertTrue(gateway.issueCommands.isEmpty())

        viewModel.retrySame()
        runCurrent()
        assertEquals(listOf("prior-key"), gateway.issueCommands.map { it.idempotencyKey })
        assertEquals(DispatchHandoffIdentityStatus.ReissueRequired, viewModel.state.value.status)
        assertNull(viewModel.state.value.oneTimeToken)
        assertTrue(store.commands.isEmpty())

        viewModel.issueReplacement()
        runCurrent()
        assertEquals(
            listOf("prior-key", "replacement-key"),
            gateway.issueCommands.map {
                it.idempotencyKey
            }
        )
        assertEquals("dsp_new_token", viewModel.state.value.oneTimeToken)
    }

    @Test
    fun validationRejectionChangesOnlyIdentityUiAndLateValidationIsFenced() = runTest {
        val store = FakeMetadataStore()
        val gateway = FakeGateway(store)
        val pending = CompletableDeferred<DispatchHandoffValidationResult>()
        gateway.validateResult = { _, _, _, _ -> pending.await() }
        val viewModel = DispatchHandoffIdentityViewModel(
            gateway,
            store,
            now = { Instant.parse("2026-10-01T09:00:00Z") }
        )
        viewModel.activate(context(), DELIVERY_ID, ASSIGNMENT_ID)
        runCurrent()
        viewModel.tokenChanged("dsp_wrong_or_expired")
        viewModel.validate()
        runCurrent()
        viewModel.hideToken()
        pending.complete(DispatchHandoffValidationResult.Rejected("HANDOFF_TOKEN_EXPIRED"))
        runCurrent()

        assertEquals(DispatchHandoffIdentityStatus.Ready, viewModel.state.value.status)
        assertNull(viewModel.state.value.identity)
        assertNull(viewModel.state.value.oneTimeToken)
        assertTrue(viewModel.state.value.enteredToken.isEmpty())
        assertTrue(gateway.issueCommands.isEmpty())
        assertTrue(store.commands.isEmpty())
    }

    @Test
    fun successfulValidationConfirmsOnlyTheIdentityTuple() = runTest {
        val store = FakeMetadataStore()
        val gateway = FakeGateway(store).apply {
            validateResult = { _, _, _, _ -> DispatchHandoffValidationResult.Validated(identity()) }
        }
        val viewModel = DispatchHandoffIdentityViewModel(
            gateway,
            store,
            now = { Instant.parse("2026-10-01T09:00:00Z") }
        )
        viewModel.activate(context(), DELIVERY_ID, ASSIGNMENT_ID)
        runCurrent()
        viewModel.tokenChanged("dsp_operator_token")
        viewModel.validate()
        runCurrent()

        assertEquals(DispatchHandoffIdentityStatus.IdentityValidated, viewModel.state.value.status)
        assertEquals(DELIVERY_ID, viewModel.state.value.identity?.deliveryId)
        assertEquals(ASSIGNMENT_ID, viewModel.state.value.identity?.assignmentId)
        assertNull(viewModel.state.value.oneTimeToken)
        assertTrue(viewModel.state.value.enteredToken.isEmpty())
        assertTrue(store.commands.isEmpty())
    }

    private fun context() = DispatchAuthorityContext(
        authorityEpoch = 8,
        identity = DispatchAuthorityIdentity(
            userId = USER_ID,
            tenantId = TENANT_ID,
            workspaceId = WORKSPACE_ID,
            membershipId = MEMBERSHIP_ID,
            permissions = setOf("logistics:read", "logistics:write")
        )
    )

    private fun command(key: String) = DispatchHandoffIdentityCommand(
        DELIVERY_ID,
        ASSIGNMENT_ID,
        key,
        dispatchHandoffIssueBody(ASSIGNMENT_ID)
    )

    private fun identity(deliveryId: String = DELIVERY_ID, assignmentId: String = ASSIGNMENT_ID) =
        DispatchHandoffIdentity(
            handoffId = HANDOFF_ID,
            deliveryId = deliveryId,
            assignmentId = assignmentId,
            deliveryVersion = 16,
            expiresAt = "2030-10-01T12:00:00Z",
            status = "ACTIVE"
        )

    private class FakeMetadataStore : DispatchHandoffIdentityMetadataStore {
        val commands = mutableListOf<DispatchHandoffIdentityCommand>()
        val events = mutableListOf<String>()
        var serializedIntent: String = ""

        override suspend fun load(
            scope: DispatchAuthorityIdentity,
            deliveryId: String,
            assignmentId: String
        ): DispatchHandoffMetadataRead = DispatchHandoffMetadataRead.Available(
            commands.firstOrNull { it.deliveryId == deliveryId && it.assignmentId == assignmentId }
        )

        override suspend fun persistIntent(
            scope: DispatchAuthorityIdentity,
            command: DispatchHandoffIdentityCommand,
            replacingIdempotencyKey: String?
        ): DispatchHandoffMetadataWrite {
            events += "persist:${command.idempotencyKey}"
            val existing = commands.firstOrNull {
                it.deliveryId == command.deliveryId && it.assignmentId == command.assignmentId
            }
            if (existing == null) {
                commands += command
            } else if (existing.idempotencyKey == replacingIdempotencyKey) {
                commands[commands.indexOf(existing)] = command
            } else if (existing != command) {
                return DispatchHandoffMetadataWrite.Conflict
            }
            serializedIntent = listOfNotNull(
                command.idempotencyKey,
                command.frozenBody
            ).joinToString("|")
            return DispatchHandoffMetadataWrite.Saved
        }

        override suspend fun clearCommand(
            scope: DispatchAuthorityIdentity,
            deliveryId: String,
            assignmentId: String,
            idempotencyKey: String
        ): DispatchHandoffMetadataWrite {
            val existing = commands.firstOrNull {
                it.deliveryId == deliveryId && it.assignmentId == assignmentId
            } ?: return DispatchHandoffMetadataWrite.Saved
            if (existing.idempotencyKey != idempotencyKey) return DispatchHandoffMetadataWrite.Stale
            commands.remove(existing)
            return DispatchHandoffMetadataWrite.Saved
        }
    }

    private inner class FakeGateway(private val store: FakeMetadataStore) :
        DispatchHandoffIdentityGateway {
        val issueCommands = mutableListOf<DispatchHandoffIdentityCommand>()
        val events = store.events
        val issueResponses = ArrayDeque<DispatchHandoffIssueResult>()
        var validateResult: suspend (
            String,
            String,
            String,
            DispatchAuthorityContext
        ) -> DispatchHandoffValidationResult =
            { _, _, _, _ -> DispatchHandoffValidationResult.Rejected("HANDOFF_TOKEN_INVALID") }

        override suspend fun issue(
            command: DispatchHandoffIdentityCommand,
            context: DispatchAuthorityContext
        ): DispatchHandoffIssueResult {
            check(store.commands.any { it == command })
            events += "issue:${command.idempotencyKey}"
            issueCommands += command
            return issueResponses.removeFirstOrNull() ?: DispatchHandoffIssueResult.Issued(
                identity(),
                "dsp_one_time_secret"
            )
        }

        override suspend fun validate(
            deliveryId: String,
            assignmentId: String,
            token: String,
            context: DispatchAuthorityContext
        ): DispatchHandoffValidationResult =
            validateResult(deliveryId, assignmentId, token, context)
    }

    private companion object {
        const val USER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413901"
        const val TENANT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413902"
        const val WORKSPACE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413903"
        const val MEMBERSHIP_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413904"
        const val DELIVERY_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413001"
        const val ASSIGNMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413003"
        const val HANDOFF_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413004"
    }
}
