package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryPodGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryPodSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPodEvidence
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchReadinessGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchReadinessGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartConfirmation
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.SealDispatchPodCommand
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadinessLine
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchReadinessViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun currentListCanLoadMatchingDetailWithServerVersionsAndReadiness() = runTest {
        val gateway = FakeGateway(readyReadiness())
        val viewModel = DispatchReadinessViewModel(gateway, FakeTransitMetadataStore())

        viewModel.activate(context())
        runCurrent()
        assertEquals(DispatchReadinessStatus.Current, viewModel.state.value.status)
        assertEquals(FULFILLMENT_ID, viewModel.state.value.items.single().fulfillmentId)
        assertEquals(AS_OF, viewModel.state.value.asOf)

        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()
        assertEquals(DispatchReadinessDetailStatus.Current, viewModel.state.value.detailStatus)
        assertEquals(readyReadiness(), viewModel.state.value.detail)
        assertEquals(listOf(FULFILLMENT_ID), gateway.detailRequests)
    }

    @Test
    fun permissionBecomingUnknownAtSameEpochClearsPreviouslyUsableReadiness() = runTest {
        val gateway = FakeGateway(readyReadiness())
        val viewModel = DispatchReadinessViewModel(gateway, FakeTransitMetadataStore())

        viewModel.activate(context())
        runCurrent()
        assertEquals(1, viewModel.state.value.items.size)

        viewModel.activate(context(permissions = emptySet()))

        assertEquals(7L, viewModel.state.value.authorityEpoch)
        assertEquals(DispatchReadinessStatus.PermissionUnknown, viewModel.state.value.status)
        assertTrue(viewModel.state.value.items.isEmpty())
        assertNull(viewModel.state.value.detail)
    }

    @Test
    fun lateDetailAfterContextInvalidationDoesNotRestoreReadiness() = runTest {
        val gateway = FakeGateway(readyReadiness())
        val delayed = CompletableDeferred<DispatchReadinessGatewayResult>()
        gateway.detailResult = { delayed.await() }
        val viewModel = DispatchReadinessViewModel(gateway, FakeTransitMetadataStore())

        viewModel.activate(context())
        runCurrent()
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()
        viewModel.invalidateContext()
        delayed.complete(DispatchReadinessGatewayResult.Detail(readyReadiness()))
        runCurrent()

        assertEquals(DispatchReadinessStatus.ContextInvalidated, viewModel.state.value.status)
        assertTrue(viewModel.state.value.items.isEmpty())
        assertNull(viewModel.state.value.detail)
    }

    @Test
    fun detailMustRemainTheSelectedPreparedFulfillmentAndAllocation() = runTest {
        val gateway = FakeGateway(readyReadiness())
        gateway.detailResult = {
            DispatchReadinessGatewayResult.Detail(
                readyReadiness().copy(physicalAllocationId = OTHER_ALLOCATION_ID)
            )
        }
        val viewModel = DispatchReadinessViewModel(gateway, FakeTransitMetadataStore())

        viewModel.activate(context())
        runCurrent()
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()

        assertEquals(
            DispatchReadinessDetailStatus.ServiceUnavailable,
            viewModel.state.value.detailStatus
        )
        assertNull(viewModel.state.value.detail)
    }

    @Test
    fun capturedPodCanBeSealedOnlyWithCurrentDeliveryAndPermission() = runTest {
        val gateway = FakeGateway(deliveredReadiness())
        gateway.podResult = { DispatchDeliveryPodGatewayResult.Snapshot(deliveryPod()) }
        gateway.sealResult = {
            DispatchDeliveryPodGatewayResult.Sealed(podEvidence("SEALED", version = 18))
        }
        val viewModel = DispatchReadinessViewModel(
            gateway,
            FakeTransitMetadataStore(),
            newCommandKey = { "stable-pod-seal-key" }
        )

        viewModel.activate(context(setOf("dispatch.read", "dispatch.complete")))
        runCurrent()
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()

        assertEquals(DispatchDeliveryPodStatus.Current, viewModel.state.value.deliveryPodStatus)
        assertTrue(viewModel.canSealPod())
        viewModel.sealPod()
        runCurrent()

        assertEquals(DispatchPodSealStatus.Sealed, viewModel.state.value.podSealStatus)
        assertEquals("SEALED", viewModel.state.value.deliveryPod?.pod?.status)
        assertEquals(listOf("stable-pod-seal-key"), gateway.sealCommands.map { it.idempotencyKey })
        assertEquals("\"17\"", gateway.sealCommands.single().deliveryEtag)
        assertEquals(18L, viewModel.state.value.deliveryPod?.deliveryVersion)
    }

    @Test
    fun unknownSealOutcomeRetriesTheSameCommandIdentity() = runTest {
        val gateway = FakeGateway(deliveredReadiness())
        gateway.podResult = { DispatchDeliveryPodGatewayResult.Snapshot(deliveryPod()) }
        gateway.sealResult = { DispatchDeliveryPodGatewayResult.UnknownOutcome }
        val viewModel = DispatchReadinessViewModel(
            gateway,
            FakeTransitMetadataStore(),
            newCommandKey = { "retry-pod-seal-key" }
        )

        viewModel.activate(context(setOf("dispatch.read", "logistics:write")))
        runCurrent()
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()
        viewModel.sealPod()
        runCurrent()
        assertEquals(DispatchPodSealStatus.UnknownOutcome, viewModel.state.value.podSealStatus)

        gateway.sealResult = {
            DispatchDeliveryPodGatewayResult.Sealed(podEvidence("SEALED", version = 18))
        }
        viewModel.retryPodSeal()
        runCurrent()

        assertEquals(DispatchPodSealStatus.Sealed, viewModel.state.value.podSealStatus)
        assertEquals(2, gateway.sealCommands.size)
        assertEquals(gateway.sealCommands[0], gateway.sealCommands[1])
    }

    @Test
    fun podCannotBeSealedWhenCurrentContextLacksCompletionPermission() = runTest {
        val gateway = FakeGateway(deliveredReadiness())
        gateway.podResult = { DispatchDeliveryPodGatewayResult.Snapshot(deliveryPod()) }
        val viewModel = DispatchReadinessViewModel(gateway, FakeTransitMetadataStore())

        viewModel.activate(context(setOf("dispatch.read")))
        runCurrent()
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()

        assertTrue(!viewModel.canSealPod())
        viewModel.sealPod()
        runCurrent()
        assertTrue(gateway.sealCommands.isEmpty())
    }

    @Test
    fun transitStartPersistsExactIntentAndReconcilesServerConfirmation() = runTest {
        val metadata = FakeTransitMetadataStore()
        val gateway = FakeGateway(dispatchedReadiness()).apply {
            podResult = { DispatchDeliveryPodGatewayResult.Snapshot(dispatchedDeliveryPod()) }
            transitResult = {
                DispatchTransitStartGatewayResult.Started(
                    DispatchTransitStartConfirmation(
                        deliveryId = DELIVERY_ID,
                        fulfillmentId = FULFILLMENT_ID,
                        deliveryStatus = "IN_TRANSIT",
                        deliveryVersion = 18,
                        deliveryEtag = "\"18\""
                    )
                )
            }
            beforeTransit = {
                assertEquals(
                    DispatchTransitStartIntentStatus.Pending,
                    metadata.saved?.status
                )
            }
        }
        val viewModel = DispatchReadinessViewModel(
            gateway,
            metadata,
            newCommandKey = { "stable-transit-start-key" }
        )
        viewModel.activate(context(setOf("dispatch.read", "logistics:write")))
        runCurrent()
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()

        assertTrue(viewModel.canStartTransit())
        viewModel.startTransit()
        runCurrent()

        assertEquals(1, gateway.transitIntents.size)
        val (intent, replayUnknown) = gateway.transitIntents.single()
        assertEquals(DispatchTransitStartIntentStatus.Pending, intent.status)
        assertEquals("stable-transit-start-key", intent.command.idempotencyKey)
        assertEquals("\"17\"", intent.command.deliveryEtag)
        assertTrue(!replayUnknown)
        assertEquals(DispatchTransitStartStatus.Started, viewModel.state.value.transitStartStatus)
        assertEquals("IN_TRANSIT", viewModel.state.value.deliveryPod?.deliveryStatus)
        assertEquals(18L, viewModel.state.value.deliveryPod?.deliveryVersion)
        assertEquals("\"18\"", viewModel.state.value.deliveryPod?.deliveryEtag)
        assertNull(metadata.saved)
    }

    @Test
    fun unknownTransitStartRetriesPersistedCommandWithSameIdentity() = runTest {
        val metadata = FakeTransitMetadataStore()
        val gateway = FakeGateway(dispatchedReadiness()).apply {
            podResult = { DispatchDeliveryPodGatewayResult.Snapshot(dispatchedDeliveryPod()) }
            transitResult = { DispatchTransitStartGatewayResult.UnknownOutcome }
        }
        val viewModel = DispatchReadinessViewModel(
            gateway,
            metadata,
            newCommandKey = { "retry-transit-start-key" }
        )
        viewModel.activate(context(setOf("dispatch.read", "logistics:write")))
        runCurrent()
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()
        viewModel.startTransit()
        runCurrent()

        assertEquals(
            DispatchTransitStartStatus.UnknownOutcome,
            viewModel.state.value.transitStartStatus
        )
        assertEquals(DispatchTransitStartIntentStatus.UnknownOutcome, metadata.saved?.status)
        assertTrue(viewModel.canRetryTransitStart())
        gateway.transitResult = {
            DispatchTransitStartGatewayResult.Started(
                DispatchTransitStartConfirmation(
                    deliveryId = DELIVERY_ID,
                    fulfillmentId = FULFILLMENT_ID,
                    deliveryStatus = "IN_TRANSIT",
                    deliveryVersion = 18,
                    deliveryEtag = "\"18\""
                )
            )
        }
        viewModel.retryTransitStart()
        runCurrent()

        assertEquals(2, gateway.transitIntents.size)
        assertEquals(
            gateway.transitIntents[0].first.command,
            gateway.transitIntents[1].first.command
        )
        assertTrue(gateway.transitIntents[1].second)
        assertEquals(DispatchTransitStartStatus.Started, viewModel.state.value.transitStartStatus)
        assertNull(metadata.saved)
    }

    @Test
    fun serviceUnavailableAfterPostKeepsUnknownIntentAndReusesItsKey() = runTest {
        val metadata = FakeTransitMetadataStore()
        val gateway = FakeGateway(dispatchedReadiness()).apply {
            podResult = { DispatchDeliveryPodGatewayResult.Snapshot(dispatchedDeliveryPod()) }
            transitResult = { DispatchTransitStartGatewayResult.ServiceUnavailable }
        }
        val viewModel = DispatchReadinessViewModel(
            gateway,
            metadata,
            newCommandKey = { "service-unavailable-transit-key" }
        )
        viewModel.activate(context(setOf("dispatch.read", "logistics:write")))
        runCurrent()
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()

        viewModel.startTransit()
        runCurrent()

        assertEquals(
            DispatchTransitStartStatus.UnknownOutcome,
            viewModel.state.value.transitStartStatus
        )
        assertEquals(DispatchTransitStartIntentStatus.UnknownOutcome, metadata.saved?.status)
        assertEquals("service-unavailable-transit-key", metadata.saved?.command?.idempotencyKey)

        gateway.transitResult = {
            DispatchTransitStartGatewayResult.Started(
                DispatchTransitStartConfirmation(
                    deliveryId = DELIVERY_ID,
                    fulfillmentId = FULFILLMENT_ID,
                    deliveryStatus = "IN_TRANSIT",
                    deliveryVersion = 18,
                    deliveryEtag = "\"18\""
                )
            )
        }
        viewModel.retryTransitStart()
        runCurrent()

        assertEquals(2, gateway.transitIntents.size)
        assertEquals(
            gateway.transitIntents[0].first.command,
            gateway.transitIntents[1].first.command
        )
        assertTrue(gateway.transitIntents[1].second)
        assertEquals(DispatchTransitStartStatus.Started, viewModel.state.value.transitStartStatus)
        assertNull(metadata.saved)
    }

    @Test
    fun transitCompletionAfterScopeChangesDuringMetadataClearDoesNotUpdateNewState() = runTest {
        val metadata = FakeTransitMetadataStore()
        val clearStarted = CompletableDeferred<Unit>()
        val releaseClear = CompletableDeferred<Unit>()
        metadata.beforeClear = {
            clearStarted.complete(Unit)
            releaseClear.await()
        }
        val gateway = FakeGateway(dispatchedReadiness()).apply {
            podResult = { DispatchDeliveryPodGatewayResult.Snapshot(dispatchedDeliveryPod()) }
            transitResult = {
                DispatchTransitStartGatewayResult.Started(
                    DispatchTransitStartConfirmation(
                        deliveryId = DELIVERY_ID,
                        fulfillmentId = FULFILLMENT_ID,
                        deliveryStatus = "IN_TRANSIT",
                        deliveryVersion = 18,
                        deliveryEtag = "\"18\""
                    )
                )
            }
        }
        val viewModel = DispatchReadinessViewModel(
            gateway,
            metadata,
            newCommandKey = { "scope-drift-transit-key" }
        )
        viewModel.activate(context(setOf("dispatch.read", "logistics:write")))
        runCurrent()
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()

        viewModel.startTransit()
        runCurrent()
        assertTrue(clearStarted.isCompleted)

        val nextContext = context(
            setOf("dispatch.read", "logistics:write"),
            userId = OTHER_USER_ID
        )
        viewModel.activate(nextContext)
        runCurrent()
        assertEquals(DispatchReadinessStatus.Current, viewModel.state.value.status)
        assertEquals(DispatchTransitStartStatus.Idle, viewModel.state.value.transitStartStatus)

        releaseClear.complete(Unit)
        runCurrent()

        assertEquals(nextContext.authorityEpoch, viewModel.state.value.authorityEpoch)
        assertEquals(DispatchTransitStartStatus.Idle, viewModel.state.value.transitStartStatus)
        assertNull(viewModel.state.value.pendingTransitStart)
    }

    @Test
    fun replayWithOlderConfirmationKeepsNewerServerSnapshot() = runTest {
        val metadata = FakeTransitMetadataStore()
        val gateway = FakeGateway(dispatchedReadiness()).apply {
            podResult = { DispatchDeliveryPodGatewayResult.Snapshot(dispatchedDeliveryPod()) }
            transitResult = { DispatchTransitStartGatewayResult.UnknownOutcome }
        }
        val viewModel = DispatchReadinessViewModel(
            gateway,
            metadata,
            newCommandKey = { "older-transit-replay-key" }
        )
        viewModel.activate(context(setOf("dispatch.read", "logistics:write")))
        runCurrent()
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()
        viewModel.startTransit()
        runCurrent()

        val newerSnapshot = dispatchedDeliveryPod("DELIVERED").copy(
            deliveryVersion = 20,
            deliveryEtag = "\"20\""
        )
        gateway.podResult = { DispatchDeliveryPodGatewayResult.Snapshot(newerSnapshot) }
        viewModel.refreshDeliveryPod()
        runCurrent()
        assertEquals(20L, viewModel.state.value.deliveryPod?.deliveryVersion)

        gateway.transitResult = {
            DispatchTransitStartGatewayResult.Started(
                DispatchTransitStartConfirmation(
                    deliveryId = DELIVERY_ID,
                    fulfillmentId = FULFILLMENT_ID,
                    deliveryStatus = "IN_TRANSIT",
                    deliveryVersion = 18,
                    deliveryEtag = "\"18\""
                )
            )
        }
        viewModel.retryTransitStart()
        runCurrent()

        assertEquals(DispatchTransitStartStatus.Started, viewModel.state.value.transitStartStatus)
        assertEquals("DELIVERED", viewModel.state.value.deliveryPod?.deliveryStatus)
        assertEquals(20L, viewModel.state.value.deliveryPod?.deliveryVersion)
        assertEquals("DELIVERED", viewModel.state.value.detail?.deliveryStatus)
        assertEquals(20L, viewModel.state.value.detail?.deliveryVersion)
        assertNull(metadata.saved)
    }

    @Test
    fun sameVersionReplayDisagreementStaysUnknownAndRefreshesDelivery() = runTest {
        val metadata = FakeTransitMetadataStore()
        val gateway = FakeGateway(dispatchedReadiness()).apply {
            podResult = { DispatchDeliveryPodGatewayResult.Snapshot(dispatchedDeliveryPod()) }
            transitResult = { DispatchTransitStartGatewayResult.UnknownOutcome }
        }
        val viewModel = DispatchReadinessViewModel(
            gateway,
            metadata,
            newCommandKey = { "same-version-transit-replay-key" }
        )
        viewModel.activate(context(setOf("dispatch.read", "logistics:write")))
        runCurrent()
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()
        viewModel.startTransit()
        runCurrent()

        val currentSnapshot = dispatchedDeliveryPod("DELIVERED").copy(
            deliveryVersion = 18,
            deliveryEtag = "\"18\""
        )
        gateway.podResult = { DispatchDeliveryPodGatewayResult.Snapshot(currentSnapshot) }
        viewModel.refreshDeliveryPod()
        runCurrent()
        assertEquals(18L, viewModel.state.value.deliveryPod?.deliveryVersion)

        gateway.transitResult = {
            DispatchTransitStartGatewayResult.Started(
                DispatchTransitStartConfirmation(
                    deliveryId = DELIVERY_ID,
                    fulfillmentId = FULFILLMENT_ID,
                    deliveryStatus = "IN_TRANSIT",
                    deliveryVersion = 18,
                    deliveryEtag = "\"18\""
                )
            )
        }
        viewModel.retryTransitStart()
        runCurrent()

        assertEquals(
            DispatchTransitStartStatus.UnknownOutcome,
            viewModel.state.value.transitStartStatus
        )
        assertEquals("DELIVERED", viewModel.state.value.deliveryPod?.deliveryStatus)
        assertEquals(18L, viewModel.state.value.deliveryPod?.deliveryVersion)
        assertEquals(DispatchTransitStartIntentStatus.UnknownOutcome, metadata.saved?.status)
        assertEquals(3, gateway.deliveryPodRequests.size)
    }

    @Test
    fun firstTransitPostCannotReconcileLaterDeliveryStatusAsItsOwnTransition() = runTest {
        val metadata = FakeTransitMetadataStore()
        val gateway = FakeGateway(dispatchedReadiness()).apply {
            podResult = { DispatchDeliveryPodGatewayResult.Snapshot(dispatchedDeliveryPod()) }
            transitResult = {
                DispatchTransitStartGatewayResult.Started(
                    DispatchTransitStartConfirmation(
                        deliveryId = DELIVERY_ID,
                        fulfillmentId = FULFILLMENT_ID,
                        deliveryStatus = "DELIVERED",
                        deliveryVersion = 18,
                        deliveryEtag = "\"18\""
                    )
                )
            }
        }
        val viewModel = DispatchReadinessViewModel(gateway, metadata)
        viewModel.activate(context(setOf("dispatch.read", "logistics:write")))
        runCurrent()
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()

        viewModel.startTransit()
        runCurrent()

        assertEquals(
            DispatchTransitStartStatus.UnknownOutcome,
            viewModel.state.value.transitStartStatus
        )
        assertEquals("DISPATCHED", viewModel.state.value.deliveryPod?.deliveryStatus)
        assertEquals(DispatchTransitStartIntentStatus.UnknownOutcome, metadata.saved?.status)
    }

    @Test
    fun transitStartRequiresDispatchedSnapshotAndCurrentLogisticsPermission() = runTest {
        val metadata = FakeTransitMetadataStore()
        val gateway = FakeGateway(dispatchedReadiness()).apply {
            podResult = { DispatchDeliveryPodGatewayResult.Snapshot(dispatchedDeliveryPod()) }
        }
        val viewModel = DispatchReadinessViewModel(gateway, metadata)
        viewModel.activate(context(setOf("dispatch.read")))
        runCurrent()
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()

        assertTrue(!viewModel.canStartTransit())
        viewModel.startTransit()
        runCurrent()
        assertTrue(metadata.writes.isEmpty())
        assertTrue(gateway.transitIntents.isEmpty())

        viewModel.activate(context(setOf("dispatch.read", "logistics:write")))
        runCurrent()
        gateway.podResult = {
            DispatchDeliveryPodGatewayResult.Snapshot(dispatchedDeliveryPod("ASSIGNED"))
        }
        viewModel.selectFulfillment(FULFILLMENT_ID)
        runCurrent()

        assertEquals("ASSIGNED", viewModel.state.value.deliveryPod?.deliveryStatus)
        assertTrue(!viewModel.canStartTransit())
        viewModel.startTransit()
        runCurrent()
        assertTrue(metadata.writes.isEmpty())
        assertTrue(gateway.transitIntents.isEmpty())
    }

    private fun context(
        permissions: Set<String> = setOf("dispatch.read"),
        userId: String = USER_ID
    ): DispatchAuthorityContext = DispatchAuthorityContext(
        authorityEpoch = 7,
        identity = DispatchAuthorityIdentity(
            userId = userId,
            tenantId = TENANT_ID,
            workspaceId = WORKSPACE_ID,
            membershipId = MEMBERSHIP_ID,
            permissions = permissions
        )
    )

    private fun readyReadiness() = DispatchReadiness(
        subjectKind = "PREPARED_FULFILLMENT",
        fulfillmentId = FULFILLMENT_ID,
        fulfillmentVersion = 12,
        fulfillmentStatus = "READY_FOR_DISPATCH",
        physicalAllocationId = ALLOCATION_ID,
        physicalAllocationStatus = "ALLOCATED",
        physicalAllocationVersion = 7,
        deliveryId = null,
        deliveryStatus = null,
        deliveryVersion = null,
        allocationComplete = true,
        pickingComplete = true,
        pickingEvidenceComplete = true,
        ready = true,
        reasons = emptyList(),
        lines = listOf(
            DispatchReadinessLine(
                fulfillmentLineId = LINE_ID,
                skuId = SKU_ID,
                catalogItemId = "CAT-42",
                allocatedQuantity = BigDecimal("3.50"),
                physicallyAllocatedQuantity = BigDecimal("3.50"),
                pickedQuantity = BigDecimal("3.50"),
                evidencedPickedQuantity = BigDecimal("3.50"),
                allocationComplete = true,
                pickingComplete = true,
                evidenceComplete = true
            )
        ),
        asOf = AS_OF
    )

    private fun deliveredReadiness() = readyReadiness().copy(
        deliveryId = DELIVERY_ID,
        deliveryStatus = "DELIVERED",
        deliveryVersion = 17
    )

    private fun dispatchedReadiness() = readyReadiness().copy(
        deliveryId = DELIVERY_ID,
        deliveryStatus = "DISPATCHED",
        deliveryVersion = 17
    )

    private fun deliveryPod() = DispatchDeliveryPodSnapshot(
        deliveryId = DELIVERY_ID,
        fulfillmentId = FULFILLMENT_ID,
        deliveryStatus = "DELIVERED",
        deliveryVersion = 17,
        deliveryEtag = "\"17\"",
        pod = podEvidence("CAPTURED")
    )

    private fun dispatchedDeliveryPod(status: String = "DISPATCHED") = DispatchDeliveryPodSnapshot(
        deliveryId = DELIVERY_ID,
        fulfillmentId = FULFILLMENT_ID,
        deliveryStatus = status,
        deliveryVersion = 17,
        deliveryEtag = "\"17\"",
        pod = null
    )

    private fun podEvidence(status: String, version: Long = 17) = DispatchPodEvidence(
        id = POD_ID,
        deliveryId = DELIVERY_ID,
        status = status,
        receiverName = "Receiver",
        capturedAt = AS_OF,
        sealedAt = if (status == "SEALED") AS_OF else null,
        deliveryVersion = version,
        deliveryEtag = "\"$version\""
    )

    private class FakeGateway(private val readiness: DispatchReadiness) : DispatchReadinessGateway {
        val detailRequests = mutableListOf<String>()
        val deliveryPodRequests = mutableListOf<String>()
        val sealCommands = mutableListOf<SealDispatchPodCommand>()
        val transitIntents = mutableListOf<Pair<DispatchTransitStartIntent, Boolean>>()
        var beforeTransit: () -> Unit = {}
        var detailResult: suspend () -> DispatchReadinessGatewayResult = {
            DispatchReadinessGatewayResult.Detail(readiness)
        }
        var podResult: suspend () -> DispatchDeliveryPodGatewayResult = {
            DispatchDeliveryPodGatewayResult.ServiceUnavailable
        }
        var sealResult: suspend () -> DispatchDeliveryPodGatewayResult = {
            DispatchDeliveryPodGatewayResult.UnknownOutcome
        }
        var transitResult: suspend () -> DispatchTransitStartGatewayResult = {
            DispatchTransitStartGatewayResult.UnknownOutcome
        }

        override suspend fun list(context: DispatchAuthorityContext) =
            DispatchReadinessGatewayResult.ListResult(listOf(readiness), AS_OF)

        override suspend fun detail(
            fulfillmentId: String,
            context: DispatchAuthorityContext
        ): DispatchReadinessGatewayResult {
            detailRequests += fulfillmentId
            return detailResult()
        }

        override suspend fun deliveryPod(
            deliveryId: String,
            context: DispatchAuthorityContext
        ): DispatchDeliveryPodGatewayResult {
            deliveryPodRequests += deliveryId
            return podResult()
        }

        override suspend fun sealPod(
            command: SealDispatchPodCommand,
            context: DispatchAuthorityContext
        ): DispatchDeliveryPodGatewayResult {
            sealCommands += command
            return sealResult()
        }

        override suspend fun startTransit(
            intent: DispatchTransitStartIntent,
            context: DispatchAuthorityContext,
            replayUnknown: Boolean
        ): DispatchTransitStartGatewayResult {
            beforeTransit()
            transitIntents += intent to replayUnknown
            return transitResult()
        }
    }

    private class FakeTransitMetadataStore : DispatchTransitStartMetadataStore {
        var saved: DispatchTransitStartIntent? = null
        val writes = mutableListOf<DispatchTransitStartIntent>()
        var beforeClear: suspend () -> Unit = {}

        override suspend fun loadIntent(
            scope: DispatchTransitStartScopeIdentity,
            deliveryId: String
        ) = DispatchTransitStartMetadataRead.Available(
            saved?.takeIf { it.scope == scope && it.command.deliveryId == deliveryId }
        )

        override suspend fun saveIntent(
            intent: DispatchTransitStartIntent
        ): DispatchTransitStartMetadataWrite {
            writes += intent
            val current = saved
            if (current != null &&
                (current.scope != intent.scope || current.command != intent.command)
            ) {
                return DispatchTransitStartMetadataWrite.Conflict
            }
            saved = intent
            return DispatchTransitStartMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: DispatchTransitStartScopeIdentity,
            deliveryId: String,
            idempotencyKey: String
        ): DispatchTransitStartMetadataWrite {
            beforeClear()
            val current = saved ?: return DispatchTransitStartMetadataWrite.Saved
            if (current.scope != scope || current.command.deliveryId != deliveryId ||
                current.command.idempotencyKey != idempotencyKey
            ) {
                return DispatchTransitStartMetadataWrite.Stale
            }
            saved = null
            return DispatchTransitStartMetadataWrite.Saved
        }
    }

    private companion object {
        val AS_OF: Instant = Instant.parse("2026-09-30T10:15:30Z")
        const val USER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413901"
        const val TENANT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413902"
        const val WORKSPACE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413903"
        const val MEMBERSHIP_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413904"
        const val OTHER_USER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413905"
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413001"
        const val OTHER_ALLOCATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413102"
        const val ALLOCATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val DELIVERY_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413501"
        const val POD_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413502"
        const val LINE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
        const val SKU_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413301"
    }
}
