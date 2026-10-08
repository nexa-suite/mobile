package com.nexa.mobile.operations.feature.delivery

import com.nexa.mobile.operations.feature.delivery.application.DriverExecutionTemperatureGateway
import com.nexa.mobile.operations.feature.delivery.application.DriverExecutionTemperatureMetadataStore
import com.nexa.mobile.operations.feature.delivery.application.DriverIncidentGateway
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureHold
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureIntent
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureLine
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureLoadResult
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureMode
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureMutationResult
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureReading
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureSnapshot
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentCurrentDeliveryResult
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentEvidenceAttachCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentEvidenceProjection
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentEvidenceResult
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentEvidenceUploadCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentResult
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentSummary
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentType
import java.math.BigDecimal
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
class DriverExecutionTemperatureViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun excursionRequiresSameActorIncidentAndExactAvailablePhotoThenPersistsBeforePost() = runTest {
        val events = mutableListOf<String>()
        val store = MemoryMetadata(events)
        val gateway = MemoryGateway(events)
        val incidents = MemoryIncidents().apply {
            photo = DriverIncidentEvidenceResult.Current(
                DriverIncidentEvidenceProjection(
                    EVIDENCE_ID,
                    "DELIVERY_INCIDENT",
                    INCIDENT_ID,
                    "AVAILABLE",
                    "image/jpeg",
                    "a".repeat(64),
                    10
                )
            )
        }
        val vm =
            DriverExecutionTemperatureViewModel(gateway, incidents, store, {
                NOW
            }, { "fixed-excursion-key" })
        vm.activate(AUTHORITY, DELIVERY_ID, DriverExecutionTemperatureMode.DRIVER)
        advanceUntilIdle()
        vm.updateQuantity(LINE_ID, "1")
        vm.updateCelsius(LINE_ID, "-5")
        vm.record(LINE_ID, "1", "-5")
        advanceUntilIdle()
        assertTrue(gateway.recorded.isEmpty())

        vm.selectRecordedIncident(summary(), EVIDENCE_ID)
        advanceUntilIdle()
        assertEquals(
            DriverExecutionTemperatureEvidenceStatus.Available,
            vm.state.value.sourceEvidenceStatus
        )
        vm.record(LINE_ID, "1", "-5")
        advanceUntilIdle()

        assertEquals(listOf("save", "post"), events)
        val command = gateway.recorded.single()
        assertEquals(INCIDENT_ID, command.sourceIncidentId)
        assertEquals(EVIDENCE_ID, command.evidenceObjectId)
        assertEquals("fixed-excursion-key", command.idempotencyKey)
        assertEquals(7L, command.expectedDeliveryVersion)
        assertEquals("HELD", gateway.lastRecordedHold?.status)
        assertEquals(DriverExecutionTemperatureCommandStatus.Recorded, vm.state.value.commandStatus)
    }

    @Test
    fun unrelatedPhotoAndDifferentReporterNeverEnableExcursionWrite() = runTest {
        val gateway = MemoryGateway()
        val incidents = MemoryIncidents().apply {
            photo = DriverIncidentEvidenceResult.Current(
                DriverIncidentEvidenceProjection(
                    EVIDENCE_ID,
                    "DELIVERY_INCIDENT",
                    OTHER_INCIDENT_ID,
                    "AVAILABLE",
                    "image/jpeg",
                    "b".repeat(64),
                    10
                )
            )
        }
        val vm =
            DriverExecutionTemperatureViewModel(gateway, incidents, MemoryMetadata(), {
                NOW
            }, { "unused" })
        vm.activate(AUTHORITY, DELIVERY_ID, DriverExecutionTemperatureMode.DRIVER)
        advanceUntilIdle()
        vm.selectRecordedIncident(summary(actor = OTHER_MEMBERSHIP_ID), EVIDENCE_ID)
        advanceUntilIdle()
        assertTrue(gateway.recorded.isEmpty())
        assertNull(vm.state.value.sourceIncidentId)
        assertEquals(
            DriverExecutionTemperatureEvidenceStatus.None,
            vm.state.value.sourceEvidenceStatus
        )
    }

    @Test
    fun unknownOutcomeRequiresManualRetryAndReusesFrozenKeyAndBody() = runTest {
        val events = mutableListOf<String>()
        val store = MemoryMetadata(events)
        val gateway = MemoryGateway(events).apply { unknownOnce = true }
        val vm =
            DriverExecutionTemperatureViewModel(gateway, MemoryIncidents(), store, {
                NOW
            }, { "stable-key" })
        vm.activate(AUTHORITY, DELIVERY_ID, DriverExecutionTemperatureMode.DRIVER)
        advanceUntilIdle()
        vm.updateQuantity(LINE_ID, "1")
        vm.updateCelsius(LINE_ID, "4")
        vm.record(LINE_ID, "1", "4")
        advanceUntilIdle()
        assertEquals(listOf("save", "post", "save"), events)
        assertTrue(vm.state.value.canRetryUnknownOutcome)
        val original = gateway.recorded.single()

        vm.retryUnknownOutcome()
        advanceUntilIdle()
        assertEquals(2, gateway.recorded.size)
        assertEquals(original.idempotencyKey, gateway.recorded.last().idempotencyKey)
        assertEquals(original.frozenBody, gateway.recorded.last().frozenBody)
        assertFalse(vm.state.value.hasRecoverableCommand)
        assertEquals(DriverExecutionTemperatureCommandStatus.Recorded, vm.state.value.commandStatus)
    }

    @Test
    fun deactivationDropsLateScopedRead() = runTest {
        val pending = CompletableDeferred<DriverExecutionTemperatureLoadResult>()
        val gateway = MemoryGateway().apply { nextRead = pending }
        val vm =
            DriverExecutionTemperatureViewModel(gateway, MemoryIncidents(), MemoryMetadata(), {
                NOW
            }, { "unused" })
        vm.activate(AUTHORITY, DELIVERY_ID, DriverExecutionTemperatureMode.DRIVER)
        runCurrent()
        vm.deactivate()
        pending.complete(DriverExecutionTemperatureLoadResult.Loaded(snapshot(version = 99)))
        advanceUntilIdle()
        assertNull(vm.state.value.snapshot)
        assertEquals(DriverExecutionTemperatureLoadStatus.NotRequested, vm.state.value.loadStatus)
    }

    private inner class MemoryGateway(private val events: MutableList<String> = mutableListOf()) :
        DriverExecutionTemperatureGateway {
        val recorded = mutableListOf<DriverExecutionTemperatureCommand.Reading>()
        var unknownOnce = false
        var lastRecordedHold: DriverExecutionTemperatureHold? = null
        var nextRead: CompletableDeferred<DriverExecutionTemperatureLoadResult>? = null
        override suspend fun current(
            deliveryId: String,
            mode: DriverExecutionTemperatureMode,
            authority: DriverDeliveryAuthority
        ): DriverExecutionTemperatureLoadResult = nextRead?.await()
            ?: DriverExecutionTemperatureLoadResult.Loaded(snapshot())

        override suspend fun record(
            command: DriverExecutionTemperatureCommand.Reading,
            authority: DriverDeliveryAuthority
        ): DriverExecutionTemperatureMutationResult {
            events += "post"
            recorded += command
            if (unknownOnce) {
                unknownOnce = false
                return DriverExecutionTemperatureMutationResult.UnknownOutcome
            }
            val outOfRange = command.sourceIncidentId != null
            val hold = if (outOfRange) hold() else null
            lastRecordedHold = hold
            return DriverExecutionTemperatureMutationResult.ReadingRecorded(
                DriverExecutionTemperatureReading(
                    READING_ID, DELIVERY_ID,
                    ATTEMPT_ID, LINE_ID, SKU_ID, command.affectedQuantity, "EA",
                    command.valueCelsius, "CELSIUS", BigDecimal("2"), BigDecimal("8"),
                    if (outOfRange) "OUT_OF_RANGE" else "WITHIN_RANGE",
                    MEMBERSHIP_ID, command.occurredAt,
                    NOW, command.evidenceObjectId, command.sourceIncidentId, hold, 8, false
                )
            )
        }
        override suspend fun dispose(
            command: DriverExecutionTemperatureCommand.Disposition,
            authority: DriverDeliveryAuthority
        ): DriverExecutionTemperatureMutationResult =
            DriverExecutionTemperatureMutationResult.UnknownOutcome
    }

    private class MemoryMetadata(private val events: MutableList<String> = mutableListOf()) :
        DriverExecutionTemperatureMetadataStore {
        var intent: DriverExecutionTemperatureIntent? = null
        override suspend fun loadIntent(scope: DriverAttemptScopeIdentity) =
            DriverExecutionTemperatureMetadataRead.Available(intent?.takeIf { it.scope == scope })
        override suspend fun saveIntent(
            intent: DriverExecutionTemperatureIntent
        ): DriverExecutionTemperatureMetadataWrite {
            events += "save"
            val old = this.intent
            if (old != null &&
                old.command != intent.command
            ) {
                return DriverExecutionTemperatureMetadataWrite.Conflict
            }
            this.intent = intent
            return DriverExecutionTemperatureMetadataWrite.Saved
        }
        override suspend fun clearIntent(
            scope: DriverAttemptScopeIdentity,
            idempotencyKey: String
        ): DriverExecutionTemperatureMetadataWrite {
            if (intent?.scope != scope || intent?.command?.idempotencyKey != idempotencyKey) {
                return DriverExecutionTemperatureMetadataWrite.Stale
            }
            intent = null
            return DriverExecutionTemperatureMetadataWrite.Saved
        }
    }

    private class MemoryIncidents : DriverIncidentGateway {
        var photo: DriverIncidentEvidenceResult = DriverIncidentEvidenceResult.Unavailable
        override suspend fun currentDelivery(
            deliveryId: String,
            authority: DriverDeliveryAuthority
        ) = DriverIncidentCurrentDeliveryResult.Unavailable
        override suspend fun recordIncident(
            command: DriverIncidentCommand,
            authority: DriverDeliveryAuthority
        ) = DriverIncidentResult.Unavailable
        override suspend fun uploadEvidence(
            command: DriverIncidentEvidenceUploadCommand,
            authority: DriverDeliveryAuthority
        ) = DriverIncidentEvidenceResult.Unavailable
        override suspend fun evidenceStatus(
            evidenceId: String,
            authority: DriverDeliveryAuthority
        ) = photo
        override suspend fun attachEvidence(
            command: DriverIncidentEvidenceAttachCommand,
            authority: DriverDeliveryAuthority
        ) = DriverIncidentResult.Unavailable
    }

    private fun summary(actor: String = MEMBERSHIP_ID) = DriverIncidentSummary(
        INCIDENT_ID, DELIVERY_ID, ATTEMPT_ID,
        "secret reason", "private details", "private place", actor,
        NOW.toString(), listOf(EVIDENCE_ID), 7, false,
        DriverIncidentType.TEMPERATURE_EXCURSION, "CRITICAL", EXCEPTION_ID
    )

    private fun snapshot(version: Long = 7) = DriverExecutionTemperatureSnapshot(
        DELIVERY_ID,
        version,
        "IN_TRANSIT",
        ATTEMPT_ID,
        WAREHOUSE_ID,
        listOf(
            DriverExecutionTemperatureLine(
                LINE_ID,
                SKU_ID,
                "EA",
                BigDecimal("5"),
                true,
                BigDecimal("2"),
                BigDecimal("8")
            )
        ),
        emptyList()
    )

    private fun hold() = DriverExecutionTemperatureHold(
        HOLD_ID, READING_ID, EXCEPTION_ID, LINE_ID, SKU_ID, BigDecimal.ONE, "EA", "HELD",
        MEMBERSHIP_ID, NOW, null, null, null, null
    )

    private companion object {
        val NOW = Instant.parse("2026-10-01T18:00:00Z")
        const val USER_ID = "11111111-1111-4111-8111-111111111111"
        const val TENANT_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val MEMBERSHIP_ID = "44444444-4444-4444-8444-444444444444"
        const val OTHER_MEMBERSHIP_ID = "55555555-5555-4555-8555-555555555555"
        const val DELIVERY_ID = "66666666-6666-4666-8666-666666666666"
        const val ATTEMPT_ID = "77777777-7777-4777-8777-777777777777"
        const val LINE_ID = "88888888-8888-4888-8888-888888888888"
        const val SKU_ID = "99999999-9999-4999-8999-999999999999"
        const val WAREHOUSE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val INCIDENT_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val OTHER_INCIDENT_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val EVIDENCE_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val EXCEPTION_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        const val HOLD_ID = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        const val READING_ID = "12121212-1212-4121-8121-121212121212"
        val AUTHORITY = DriverDeliveryAuthority(
            USER_ID,
            TENANT_ID,
            WORKSPACE_ID,
            MEMBERSHIP_ID,
            setOf("dispatch.read", "dispatch.start_route", "document.read"),
            1
        )
    }
}
