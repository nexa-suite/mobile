package com.nexa.mobile.operations.feature.dispatch

import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchTemperatureViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun outsideRangeReadingIsNotSentToTheMutationGateway() = runTest {
        val metadata = FakeMetadataStore()
        val gateway = FakeGateway(metadata)
        val viewModel = DispatchTemperatureViewModel(gateway, metadata, now = { OBSERVED_AT })
        viewModel.activate(FULFILLMENT_ID, context())
        runCurrent()
        viewModel.updateValue(LOT_ID, "9.5")

        viewModel.record(LOT_ID)
        runCurrent()

        assertEquals(DispatchTemperatureMutationStatus.OutsideRangeBackendContractGap,
            viewModel.state.value.mutationStatus)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test
    fun inRangeManualCelsiusEvidenceUsesCurrentFulfillmentVersion() = runTest {
        val metadata = FakeMetadataStore()
        val gateway = FakeGateway(metadata)
        val viewModel = DispatchTemperatureViewModel(
            gateway,
            metadata,
            now = { OBSERVED_AT },
            newCommandKey = { "temperature-key" }
        )
        viewModel.activate(FULFILLMENT_ID, context())
        runCurrent()
        viewModel.updateValue(LOT_ID, "5.5")

        viewModel.record(LOT_ID)
        runCurrent()

        assertEquals(DispatchTemperatureMutationStatus.Recorded, viewModel.state.value.mutationStatus)
        assertEquals(1, gateway.commands.size)
        assertEquals(8L, gateway.commands.single().expectedFulfillmentVersion)
        assertEquals(BigDecimal("5.5"), gateway.commands.single().valueCelsius)
        assertEquals("temperature-key", gateway.commands.single().idempotencyKey)
        assertEquals(null, metadata.intent)
    }

    @Test
    fun unknownOutcomeSurvivesRefreshAndRetriesTheSamePersistedCommand() = runTest {
        val metadata = FakeMetadataStore()
        val gateway = FakeGateway(metadata).apply { returnUnknownOnce = true }
        val viewModel = DispatchTemperatureViewModel(
            gateway,
            metadata,
            now = { OBSERVED_AT },
            newCommandKey = { "temperature-key" }
        )
        viewModel.activate(FULFILLMENT_ID, context())
        runCurrent()
        viewModel.updateValue(LOT_ID, "5.5")

        viewModel.record(LOT_ID)
        runCurrent()
        assertEquals(DispatchTemperatureMutationStatus.UnknownOutcome, viewModel.state.value.mutationStatus)
        assertEquals(DispatchTemperatureIntentStatus.UnknownOutcome, metadata.intent?.status)
        val original = gateway.commands.single()

        viewModel.refresh()
        runCurrent()
        assertEquals(DispatchTemperatureMutationStatus.UnknownOutcome, viewModel.state.value.mutationStatus)
        viewModel.retryUnknownOutcome()
        runCurrent()

        assertEquals(2, gateway.commands.size)
        assertEquals(original, gateway.commands.last())
        assertEquals(DispatchTemperatureMutationStatus.Recorded, viewModel.state.value.mutationStatus)
        assertEquals(null, metadata.intent)
    }

    private fun context() = DispatchAuthorityContext(
        authorityEpoch = 2,
        identity = DispatchAuthorityIdentity(
            userId = USER_ID,
            tenantId = TENANT_ID,
            workspaceId = WORKSPACE_ID,
            membershipId = ACTOR_ID,
            permissions = setOf("fulfillment.read", "fulfillment.manage")
        )
    )

    private class FakeGateway(private val metadata: FakeMetadataStore) : DispatchTemperatureGateway {
        val commands = mutableListOf<DispatchTemperatureCommand>()
        var returnUnknownOnce = false

        override suspend fun current(
            fulfillmentId: String,
            context: DispatchAuthorityContext
        ): DispatchTemperatureGatewayResult = DispatchTemperatureGatewayResult.Current(
            DispatchTemperatureReadiness(
                fulfillmentId = fulfillmentId,
                fulfillmentStatus = "READY_FOR_DISPATCH",
                fulfillmentVersion = 8,
                physicalAllocationId = ALLOCATION_ID,
                physicalAllocationVersion = 4,
                temperatureRequiredForFulfillment = false,
                asOf = OBSERVED_AT,
                lots = listOf(
                    DispatchTemperatureLot(
                        skuId = SKU_ID,
                        lotId = LOT_ID,
                        warehouseId = WAREHOUSE_ID,
                        zoneId = ZONE_ID,
                        skuColdChainRequired = true,
                        requiredForFulfillment = false,
                        minimumCelsius = BigDecimal("2"),
                        maximumCelsius = BigDecimal("8"),
                        status = "OPTIONAL_NOT_RECORDED",
                        latestEvidence = null
                    )
                )
            )
        )

        override suspend fun record(
            command: DispatchTemperatureCommand,
            context: DispatchAuthorityContext
        ): DispatchTemperatureGatewayResult {
            assertEquals("exact temperature command persisted before POST", true, metadata.intent != null)
            commands += command
            if (returnUnknownOnce) {
                returnUnknownOnce = false
                return DispatchTemperatureGatewayResult.UnknownOutcome
            }
            return DispatchTemperatureGatewayResult.Recorded(
                DispatchTemperatureEvidence(
                    id = EVIDENCE_ID,
                    fulfillmentId = command.fulfillmentId,
                    fulfillmentVersion = command.expectedFulfillmentVersion,
                    lotId = command.lotId,
                    valueCelsius = command.valueCelsius,
                    occurredAt = command.occurredAt,
                    actorMembershipId = ACTOR_ID,
                    status = "WITHIN_RANGE"
                )
            )
        }
    }

    private class FakeMetadataStore : DispatchTemperatureMetadataStore {
        var intent: DispatchTemperatureIntent? = null

        override suspend fun loadIntent(
            scope: DispatchTemperatureScopeIdentity,
            fulfillmentId: String
        ): DispatchTemperatureMetadataRead = DispatchTemperatureMetadataRead.Available(
            intent?.takeIf { it.scope == scope && it.command.fulfillmentId == fulfillmentId }
        )

        override suspend fun saveIntent(intent: DispatchTemperatureIntent): DispatchTemperatureMetadataWrite {
            this.intent = intent
            return DispatchTemperatureMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: DispatchTemperatureScopeIdentity,
            fulfillmentId: String,
            idempotencyKey: String
        ): DispatchTemperatureMetadataWrite {
            if (intent?.scope != scope || intent?.command?.fulfillmentId != fulfillmentId ||
                intent?.command?.idempotencyKey != idempotencyKey
            ) return DispatchTemperatureMetadataWrite.Stale
            intent = null
            return DispatchTemperatureMetadataWrite.Saved
        }
    }

    private companion object {
        const val USER_ID = "11111111-1111-4111-8111-111111111111"
        const val TENANT_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val ACTOR_ID = "44444444-4444-4444-8444-444444444444"
        const val FULFILLMENT_ID = "55555555-5555-4555-8555-555555555555"
        const val ALLOCATION_ID = "66666666-6666-4666-8666-666666666666"
        const val SKU_ID = "77777777-7777-4777-8777-777777777777"
        const val LOT_ID = "88888888-8888-4888-8888-888888888888"
        const val WAREHOUSE_ID = "99999999-9999-4999-8999-999999999999"
        const val ZONE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val EVIDENCE_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val OBSERVED_AT: Instant = Instant.parse("2026-10-01T10:15:30Z")
    }
}
