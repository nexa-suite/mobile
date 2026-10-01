package com.nexa.mobile.operations.feature.delivery

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
class DriverDeliveryOperationalExceptionsViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun claimAndReviewPersistFullScopedCommandsBeforeEachPost() = runTest {
        val events = mutableListOf<String>()
        val store = FakeMetadataStore(events)
        val gateway = FakeGateway(events)
        val keys = ArrayDeque(listOf("claim-key", "review-key"))
        val viewModel = viewModel(gateway, store, keyFactory = { keys.removeFirst() })
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()

        val open = viewModel.state.value.snapshot!!.exceptions.single()
        assertTrue(viewModel.state.value.canClaim(open))
        viewModel.claim(open.id)
        advanceUntilIdle()

        assertEquals(DriverDeliveryOperationalExceptionCommandStatus.Claimed, viewModel.state.value.commandStatus)
        assertEquals(listOf("persist", "post"), events)
        val claim = store.savedIntents.first()
        assertEquals(USER_ID, claim.scope.userId)
        assertEquals(TENANT_ID, claim.scope.tenantId)
        assertEquals(WORKSPACE_ID, claim.scope.workspaceId)
        assertEquals(MEMBERSHIP_ID, claim.scope.membershipId)
        assertEquals(MEMBERSHIP_ID, claim.initiatedByMembershipId)
        assertEquals("2026-10-01T17:00:00Z", claim.initiatedAt)
        assertEquals(DriverDeliveryOperationalExceptionAction.Claim, claim.command.action)
        assertEquals(7L, claim.command.expectedDeliveryVersion)
        assertEquals("claim-key", claim.command.idempotencyKey)
        assertEquals("{}", claim.command.frozenBody)
        assertEquals(DriverDeliveryOperationalExceptionIntentStatus.Pending, claim.status)

        val claimed = viewModel.state.value.snapshot!!.exceptions.single()
        assertEquals("CLAIMED", claimed.status)
        assertEquals(MEMBERSHIP_ID, claimed.responsibleMembershipId)
        assertTrue(viewModel.state.value.canReview(claimed))
        viewModel.sendForReview(claimed.id)
        advanceUntilIdle()

        assertEquals(DriverDeliveryOperationalExceptionCommandStatus.UnderReview, viewModel.state.value.commandStatus)
        assertEquals(listOf("persist", "post", "persist", "post"), events)
        val review = store.savedIntents.last()
        assertEquals(DriverDeliveryOperationalExceptionAction.Review, review.command.action)
        assertEquals(8L, review.command.expectedDeliveryVersion)
        assertEquals("review-key", review.command.idempotencyKey)
        assertEquals("UNDER_REVIEW", viewModel.state.value.snapshot?.exceptions?.single()?.status)
        assertEquals(MEMBERSHIP_ID, viewModel.state.value.snapshot?.exceptions?.single()?.underReviewByMembershipId)
        assertNull(store.intent)
    }

    @Test
    fun assignedWarningResolutionAndClosurePersistExactCommandsBeforePost() = runTest {
        val events = mutableListOf<String>()
        val store = FakeMetadataStore(events)
        val warning = exception(
            status = "UNDER_REVIEW",
            responsible = MEMBERSHIP_ID,
            claimedAt = "2026-10-01T17:01:00Z",
            underReviewBy = MEMBERSHIP_ID,
            underReviewAt = "2026-10-01T17:02:00Z"
        )
        val gateway = FakeGateway(events).apply {
            readResults += DriverDeliveryOperationalExceptionsLoadResult.Loaded(
                snapshot().copy(exceptions = listOf(warning))
            )
            mutationResults += DriverDeliveryOperationalExceptionMutationResult.Changed(
                mutation(
                    version = 8,
                    exception = warning.copy(
                        status = "RESOLVED",
                        resolution = "Path cleared",
                        outcome = "WARNING_CONDITION_ADDRESSED"
                    )
                )
            )
            mutationResults += DriverDeliveryOperationalExceptionMutationResult.Changed(
                mutation(
                    version = 9,
                    exception = warning.copy(
                        status = "CLOSED",
                        resolution = "Path cleared",
                        outcome = "WARNING_CONDITION_ADDRESSED"
                    )
                )
            )
        }
        val keys = ArrayDeque(listOf("resolve-key", "close-key"))
        val viewModel = viewModel(gateway, store, keyFactory = { keys.removeFirst() })
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()

        val row = viewModel.state.value.snapshot!!.exceptions.single()
        assertTrue(viewModel.state.value.canResolveWarning(row))
        assertFalse(viewModel.state.value.canCloseWarning(row))
        viewModel.resolveWarning(row.id, "  Path cleared  ")
        advanceUntilIdle()

        val resolutionCommand = store.savedIntents.last().command
        assertEquals(
            DriverDeliveryOperationalExceptionAction.ResolveWarning,
            resolutionCommand.action
        )
        assertEquals(7L, resolutionCommand.expectedDeliveryVersion)
        assertEquals("resolve-key", resolutionCommand.idempotencyKey)
        assertEquals("{\"resolution\":\"Path cleared\"}", resolutionCommand.frozenBody)
        assertEquals(listOf("persist", "post"), events)
        assertEquals("RESOLVED", viewModel.state.value.snapshot?.exceptions?.single()?.status)
        assertEquals(DriverDeliveryOperationalExceptionCommandStatus.Resolved, viewModel.state.value.commandStatus)
        assertTrue(viewModel.state.value.canCloseWarning(viewModel.state.value.snapshot!!.exceptions.single()))

        viewModel.closeWarning(row.id)
        advanceUntilIdle()

        val closureCommand = store.savedIntents.last().command
        assertEquals(DriverDeliveryOperationalExceptionAction.CloseWarning, closureCommand.action)
        assertEquals(8L, closureCommand.expectedDeliveryVersion)
        assertEquals("close-key", closureCommand.idempotencyKey)
        assertEquals("", closureCommand.frozenBody)
        assertEquals(listOf("persist", "post", "persist", "post"), events)
        assertEquals("CLOSED", viewModel.state.value.snapshot?.exceptions?.single()?.status)
        assertEquals("Path cleared", viewModel.state.value.snapshot?.exceptions?.single()?.resolution)
        assertEquals(DriverDeliveryOperationalExceptionCommandStatus.Closed, viewModel.state.value.commandStatus)
        assertNull(store.intent)
    }

    @Test
    fun completionActionsAreLimitedToOwnedSupportedWarningsAndValidResolutionText() = runTest {
        val row = exception(
            status = "UNDER_REVIEW",
            responsible = MEMBERSHIP_ID,
            claimedAt = "2026-10-01T17:01:00Z",
            underReviewBy = MEMBERSHIP_ID,
            underReviewAt = "2026-10-01T17:02:00Z"
        )
        val state = DriverDeliveryOperationalExceptionsUiState(
            canRead = true,
            canRespond = true,
            currentMembershipId = MEMBERSHIP_ID,
            deliveryId = DELIVERY_ID,
            snapshot = snapshot().copy(exceptions = listOf(row)),
            loadStatus = DriverDeliveryOperationalExceptionsLoadStatus.Ready
        )
        assertTrue(state.canResolveWarning(row))
        assertFalse(state.canResolveWarning(row.copy(type = "SITE_ACCESS")))
        assertFalse(state.canResolveWarning(row.copy(severity = "BLOCKING")))
        assertFalse(state.canResolveWarning(row.copy(responsibleMembershipId = "66666666-6666-4666-8666-666666666666")))
        assertTrue(state.canCloseWarning(row.copy(status = "RESOLVED")))
        assertFalse(state.canCloseWarning(row.copy(status = "RESOLVED", severity = "CRITICAL")))

        val gateway = FakeGateway()
        val store = FakeMetadataStore()
        val viewModel = viewModel(gateway, store)
        gateway.readResults += DriverDeliveryOperationalExceptionsLoadResult.Loaded(
            snapshot().copy(exceptions = listOf(row))
        )
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()
        viewModel.resolveWarning(row.id, "  ")
        viewModel.resolveWarning(row.id, "x".repeat(DRIVER_WARNING_RESOLUTION_MAX_CHARS + 1))
        advanceUntilIdle()

        assertTrue(gateway.commands.isEmpty())
        assertTrue(store.savedIntents.isEmpty())
    }

    @Test
    fun restoredUnknownCommandRequiresManualSameKeyRetry() = runTest {
        val original = intent(DriverDeliveryOperationalExceptionIntentStatus.UnknownOutcome)
        val store = FakeMetadataStore().apply { intent = original }
        val gateway = FakeGateway().apply {
            mutationResults += DriverDeliveryOperationalExceptionMutationResult.Changed(
                mutation(version = 8, exception = exception(status = "CLAIMED", responsible = MEMBERSHIP_ID, claimedAt = "2026-10-01T17:01:00Z"), replayed = true)
            )
        }
        val viewModel = viewModel(gateway, store)
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()

        assertEquals(DriverDeliveryOperationalExceptionCommandStatus.UnknownOutcome, viewModel.state.value.commandStatus)
        assertTrue(gateway.commands.isEmpty())
        assertEquals("OPEN", viewModel.state.value.snapshot?.exceptions?.single()?.status)

        viewModel.retrySameCommand()
        advanceUntilIdle()

        assertEquals(listOf(original.command), gateway.commands)
        assertEquals("original-key", gateway.commands.single().idempotencyKey)
        assertEquals("{}", gateway.commands.single().frozenBody)
        assertTrue(viewModel.state.value.replayed)
        assertEquals("CLAIMED", viewModel.state.value.snapshot?.exceptions?.single()?.status)
    }

    @Test
    fun restoredWarningResolutionRetriesExactFrozenReasonAndKey() = runTest {
        val original = intent(
            DriverDeliveryOperationalExceptionIntentStatus.UnknownOutcome,
            action = DriverDeliveryOperationalExceptionAction.ResolveWarning,
            frozenBody = "{\"resolution\":\"Path cleared\"}"
        )
        val resolved = exception(
            status = "RESOLVED",
            responsible = MEMBERSHIP_ID,
            claimedAt = "2026-10-01T17:01:00Z",
            underReviewBy = MEMBERSHIP_ID,
            underReviewAt = "2026-10-01T17:02:00Z",
            resolution = "Path cleared",
            outcome = "WARNING_CONDITION_ADDRESSED"
        )
        val store = FakeMetadataStore().apply { intent = original }
        val gateway = FakeGateway().apply {
            mutationResults += DriverDeliveryOperationalExceptionMutationResult.Changed(
                mutation(version = 8, exception = resolved, replayed = true)
            )
        }
        val viewModel = viewModel(gateway, store)
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()

        assertEquals(DriverDeliveryOperationalExceptionCommandStatus.UnknownOutcome, viewModel.state.value.commandStatus)
        assertTrue(gateway.commands.isEmpty())

        viewModel.retrySameCommand()
        advanceUntilIdle()

        assertEquals(listOf(original.command), gateway.commands)
        assertEquals("warning-key", gateway.commands.single().idempotencyKey)
        assertEquals("{\"resolution\":\"Path cleared\"}", gateway.commands.single().frozenBody)
        assertEquals("RESOLVED", viewModel.state.value.snapshot?.exceptions?.single()?.status)
        assertTrue(viewModel.state.value.replayed)
    }

    @Test
    fun staleVersionRefreshesWithoutRetryAndRequiresFreshClaimDecision() = runTest {
        val store = FakeMetadataStore()
        val gateway = FakeGateway().apply {
            readResults += DriverDeliveryOperationalExceptionsLoadResult.Loaded(snapshot(version = 7))
            mutationResults += DriverDeliveryOperationalExceptionMutationResult.StaleVersion
            readResults += DriverDeliveryOperationalExceptionsLoadResult.Loaded(snapshot(version = 8))
            mutationResults += DriverDeliveryOperationalExceptionMutationResult.Changed(
                mutation(version = 9, exception = exception(status = "CLAIMED", responsible = MEMBERSHIP_ID, claimedAt = "2026-10-01T17:02:00Z"))
            )
        }
        val keys = ArrayDeque(listOf("stale-key", "fresh-key"))
        val viewModel = viewModel(gateway, store, keyFactory = { keys.removeFirst() })
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()
        viewModel.claim(EXCEPTION_ID)
        advanceUntilIdle()

        assertEquals(2, gateway.reads)
        assertEquals(1, gateway.commands.size)
        assertEquals(DriverDeliveryOperationalExceptionCommandStatus.StaleVersion, viewModel.state.value.commandStatus)
        assertEquals(8L, viewModel.state.value.snapshot?.deliveryVersion)
        assertFalse(viewModel.state.value.hasRecoverableCommand)
        assertNull(store.intent)

        viewModel.claim(EXCEPTION_ID)
        advanceUntilIdle()
        assertEquals(2, gateway.commands.size)
        assertEquals("stale-key", gateway.commands.first().idempotencyKey)
        assertEquals("fresh-key", gateway.commands.last().idempotencyKey)
        assertEquals(8L, gateway.commands.last().expectedDeliveryVersion)
    }

    @Test
    fun claimedByAnotherMembershipIsVisibleButCannotBeClaimedOrReviewed() = runTest {
        val anotherMember = "66666666-6666-4666-8666-666666666666"
        val gateway = FakeGateway().apply {
            readResults += DriverDeliveryOperationalExceptionsLoadResult.Loaded(
                snapshot().copy(exceptions = listOf(exception(status = "CLAIMED", responsible = anotherMember, claimedAt = "2026-10-01T17:01:00Z")))
            )
        }
        val viewModel = viewModel(gateway, FakeMetadataStore())
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()

        val row = viewModel.state.value.snapshot!!.exceptions.single()
        assertEquals(anotherMember, row.responsibleMembershipId)
        assertFalse(viewModel.state.value.canClaim(row))
        assertFalse(viewModel.state.value.canReview(row))
        viewModel.claim(row.id)
        viewModel.sendForReview(row.id)
        advanceUntilIdle()
        assertTrue(gateway.commands.isEmpty())
    }

    @Test
    fun serverAccessRevocationClearsPreviouslyLoadedExceptionDetails() = runTest {
        val gateway = FakeGateway().apply {
            mutationResults += DriverDeliveryOperationalExceptionMutationResult.PermissionDenied
        }
        val viewModel = viewModel(gateway, FakeMetadataStore())
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.snapshot?.exceptions?.size)

        viewModel.claim(EXCEPTION_ID)
        advanceUntilIdle()

        assertNull(viewModel.state.value.snapshot)
        assertEquals(DriverDeliveryOperationalExceptionsLoadStatus.PermissionDenied, viewModel.state.value.loadStatus)
    }

    @Test
    fun serverConflictRemainsVisibleAfterFreshReadAndDoesNotClaimException() = runTest {
        val gateway = FakeGateway().apply {
            mutationResults += DriverDeliveryOperationalExceptionMutationResult.Rejected(
                "DELIVERY_CRITICAL_INSTRUCTION_ACK_REQUIRED"
            )
            readResults += DriverDeliveryOperationalExceptionsLoadResult.Loaded(snapshot(version = 7))
            readResults += DriverDeliveryOperationalExceptionsLoadResult.Loaded(snapshot(version = 8))
        }
        val viewModel = viewModel(gateway, FakeMetadataStore())
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        advanceUntilIdle()

        viewModel.claim(EXCEPTION_ID)
        advanceUntilIdle()

        assertEquals(2, gateway.reads)
        assertEquals(1, gateway.commands.size)
        assertEquals(DriverDeliveryOperationalExceptionCommandStatus.Rejected, viewModel.state.value.commandStatus)
        assertEquals("DELIVERY_CRITICAL_INSTRUCTION_ACK_REQUIRED", viewModel.state.value.rejectionCode)
        assertEquals(8L, viewModel.state.value.snapshot?.deliveryVersion)
        assertEquals("OPEN", viewModel.state.value.snapshot?.exceptions?.single()?.status)
    }

    @Test
    fun lateReadAfterAuthorityInvalidationDoesNotRestoreExceptionDetails() = runTest {
        val response = CompletableDeferred<DriverDeliveryOperationalExceptionsLoadResult>()
        val gateway = FakeGateway().apply { pendingRead = response }
        val viewModel = viewModel(gateway, FakeMetadataStore())
        viewModel.activate(AUTHORITY, DELIVERY_ID)
        runCurrent()
        viewModel.invalidate()
        response.complete(DriverDeliveryOperationalExceptionsLoadResult.Loaded(snapshot()))
        runCurrent()

        assertEquals(DriverDeliveryOperationalExceptionsUiState(), viewModel.state.value)
    }

    private fun viewModel(
        gateway: FakeGateway,
        store: FakeMetadataStore,
        keyFactory: () -> String = { "new-key" }
    ) = DriverDeliveryOperationalExceptionsViewModel(
        gateway,
        store,
        timeFactory = { "2026-10-01T17:00:00Z" },
        keyFactory = keyFactory
    )

    private class FakeGateway(private val events: MutableList<String> = mutableListOf()) :
        DriverDeliveryOperationalExceptionsGateway {
        val readResults = mutableListOf<DriverDeliveryOperationalExceptionsLoadResult>()
        val mutationResults = mutableListOf<DriverDeliveryOperationalExceptionMutationResult>()
        val commands = mutableListOf<DriverDeliveryOperationalExceptionCommand>()
        var pendingRead: CompletableDeferred<DriverDeliveryOperationalExceptionsLoadResult>? = null
        var reads = 0

        override suspend fun currentExceptions(
            deliveryId: String,
            authority: DriverDeliveryAuthority
        ): DriverDeliveryOperationalExceptionsLoadResult {
            reads++
            return pendingRead?.await() ?: readResults.removeFirstOrNull()
                ?: DriverDeliveryOperationalExceptionsLoadResult.Loaded(snapshot())
        }

        override suspend fun mutate(
            command: DriverDeliveryOperationalExceptionCommand,
            authority: DriverDeliveryAuthority
        ): DriverDeliveryOperationalExceptionMutationResult {
            events += "post"
            commands += command
            return mutationResults.removeFirstOrNull() ?: when (command.action) {
                DriverDeliveryOperationalExceptionAction.Claim -> DriverDeliveryOperationalExceptionMutationResult.Changed(
                    mutation(version = 8, exception = exception(status = "CLAIMED", responsible = MEMBERSHIP_ID, claimedAt = "2026-10-01T17:01:00Z"))
                )

                DriverDeliveryOperationalExceptionAction.Review -> DriverDeliveryOperationalExceptionMutationResult.Changed(
                    mutation(
                        version = 9,
                        exception = exception(
                            status = "UNDER_REVIEW", responsible = MEMBERSHIP_ID,
                            claimedAt = "2026-10-01T17:01:00Z", underReviewBy = MEMBERSHIP_ID,
                            underReviewAt = "2026-10-01T17:02:00Z"
                        )
                    )
                )

                DriverDeliveryOperationalExceptionAction.ResolveWarning ->
                    DriverDeliveryOperationalExceptionMutationResult.Changed(
                        mutation(
                            version = command.expectedDeliveryVersion + 1,
                            exception = exception(
                                status = "RESOLVED",
                                responsible = MEMBERSHIP_ID,
                                claimedAt = "2026-10-01T17:01:00Z",
                                underReviewBy = MEMBERSHIP_ID,
                                underReviewAt = "2026-10-01T17:02:00Z",
                                resolution = driverDeliveryOperationalExceptionResolutionFromBody(command.frozenBody),
                                outcome = "WARNING_CONDITION_ADDRESSED"
                            )
                        )
                    )

                DriverDeliveryOperationalExceptionAction.CloseWarning ->
                    DriverDeliveryOperationalExceptionMutationResult.Changed(
                        mutation(
                            version = command.expectedDeliveryVersion + 1,
                            exception = exception(
                                status = "CLOSED",
                                responsible = MEMBERSHIP_ID,
                                claimedAt = "2026-10-01T17:01:00Z",
                                underReviewBy = MEMBERSHIP_ID,
                                underReviewAt = "2026-10-01T17:02:00Z",
                                resolution = "Path cleared",
                                outcome = "WARNING_CONDITION_ADDRESSED"
                            )
                        )
                    )
            }
        }
    }

    private class FakeMetadataStore(private val events: MutableList<String> = mutableListOf()) :
        DriverDeliveryOperationalExceptionMetadataStore {
        var intent: DriverDeliveryOperationalExceptionIntent? = null
        val savedIntents = mutableListOf<DriverDeliveryOperationalExceptionIntent>()

        override suspend fun loadIntent(scope: DriverAttemptScopeIdentity) =
            DriverDeliveryOperationalExceptionMetadataRead.Available(intent?.takeIf { it.scope == scope })

        override suspend fun saveIntent(intent: DriverDeliveryOperationalExceptionIntent) =
            DriverDeliveryOperationalExceptionMetadataWrite.Saved.also {
                events += "persist"
                this.intent = intent
                savedIntents += intent
            }

        override suspend fun clearIntent(
            scope: DriverAttemptScopeIdentity,
            idempotencyKey: String
        ): DriverDeliveryOperationalExceptionMetadataWrite {
            val current = intent ?: return DriverDeliveryOperationalExceptionMetadataWrite.Saved
            if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                return DriverDeliveryOperationalExceptionMetadataWrite.Stale
            }
            intent = null
            return DriverDeliveryOperationalExceptionMetadataWrite.Saved
        }
    }

    private fun intent(
        status: DriverDeliveryOperationalExceptionIntentStatus,
        action: DriverDeliveryOperationalExceptionAction = DriverDeliveryOperationalExceptionAction.Claim,
        frozenBody: String = "{}"
    ) =
        DriverDeliveryOperationalExceptionIntent(
            scope = DriverAttemptScopeIdentity(USER_ID, TENANT_ID, WORKSPACE_ID, MEMBERSHIP_ID),
            command = DriverDeliveryOperationalExceptionCommand(
                deliveryId = DELIVERY_ID,
                exceptionId = EXCEPTION_ID,
                action = action,
                expectedDeliveryVersion = 7,
                idempotencyKey = if (action == DriverDeliveryOperationalExceptionAction.ResolveWarning) "warning-key" else "original-key",
                frozenBody = frozenBody
            ),
            initiatedByMembershipId = MEMBERSHIP_ID,
            initiatedAt = "2026-10-01T17:00:00Z",
            status = status
        )

    private companion object {
        const val DELIVERY_ID = "22222222-2222-4222-8222-222222222222"
        const val EXCEPTION_ID = "33333333-3333-4333-8333-333333333333"
        const val INCIDENT_ID = "44444444-4444-4444-8444-444444444444"
        const val USER_ID = "11111111-1111-4111-8111-111111111111"
        const val TENANT_ID = "55555555-5555-4555-8555-555555555555"
        const val WORKSPACE_ID = "77777777-7777-4777-8777-777777777777"
        const val MEMBERSHIP_ID = "88888888-8888-4888-8888-888888888888"

        val AUTHORITY = DriverDeliveryAuthority(
            USER_ID, TENANT_ID, WORKSPACE_ID, MEMBERSHIP_ID,
            setOf("dispatch.read", "dispatch.start_route"), 5L
        )

        fun snapshot(version: Long = 7) = DriverDeliveryOperationalExceptionsSnapshot(
            DELIVERY_ID,
            version,
            listOf(exception())
        )

        fun exception(
            type: String = "DELAY",
            severity: String = "WARNING",
            status: String = "OPEN",
            responsible: String? = null,
            claimedAt: String? = null,
            underReviewBy: String? = null,
            underReviewAt: String? = null,
            resolution: String? = null,
            outcome: String? = null
        ) = DriverDeliveryOperationalException(
            id = EXCEPTION_ID,
            sourceKind = "DRIVER_INCIDENT",
            sourceIncidentId = INCIDENT_ID,
            affectedObjectType = "DELIVERY",
            affectedObjectId = DELIVERY_ID,
            type = type,
            severity = severity,
            status = status,
            reason = "Road closure",
            description = "The delivery is blocked at the site.",
            place = "North entrance",
            resolution = resolution,
            outcome = outcome,
            reportedByMembershipId = MEMBERSHIP_ID,
            occurredAt = "2026-10-01T16:55:00Z",
            reportedAt = "2026-10-01T16:58:00Z",
            responsibleMembershipId = responsible,
            claimedAt = claimedAt,
            underReviewByMembershipId = underReviewBy,
            underReviewAt = underReviewAt,
            evidenceObjectIds = emptyList()
        )

        fun mutation(
            version: Long,
            exception: DriverDeliveryOperationalException,
            replayed: Boolean = false
        ) = DriverDeliveryOperationalExceptionMutation(DELIVERY_ID, version, exception, replayed)
    }
}
