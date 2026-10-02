@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexa.mobile.operations.feature.warehouse

import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TemperatureEvidenceViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun selectedServerSubjectAndOutOfRangeResponseAreShownWithoutLocalDisposition() = runTest {
        val store = MemoryMetadataStore()
        val gateway = FakeGateway().apply {
            submitResults += TemperatureSubmitResult.Confirmed(facts(status = "OUT_OF_RANGE"))
            lifecycleEvents = store.events
        }
        val viewModel = viewModel(gateway, store)
        viewModel.activate(authority())
        advanceUntilIdle()
        val listed = gateway.subjectCalls.single()
        assertEquals(TemperatureEvidenceSubjectType.LOT, listed)
        assertEquals("LOT-A · ACTIVE", viewModel.state.value.subjects.single().primaryLabel)

        viewModel.selectSubject(viewModel.state.value.subjects.single())
        viewModel.valueChanged("-18.765432100")
        viewModel.unitChanged(TemperatureEvidenceUnit.CELSIUS)
        viewModel.occurredAtChanged("2026-09-30T10:22:33-05:00")
        viewModel.stageAndRecord()
        advanceUntilIdle()

        val submitted = gateway.commands.single()
        assertEquals("-18.765432100", submitted.first.value)
        assertEquals("2026-09-30T15:22:33Z", submitted.first.occurredAt)
        assertEquals("temperature-key-1", submitted.second)
        assertEquals(TemperatureCommandStatus.Confirmed, viewModel.state.value.command)
        assertEquals("OUT_OF_RANGE", viewModel.state.value.confirmed?.status)
        assertNull(viewModel.state.value.notice)
        assertEquals(listOf("intent", "request", "clear"), store.events)
        assertFalse(store.draft?.toString().orEmpty().contains("temperature-key-1"))
    }

    @Test
    fun unknownOutcomeKeepsFrozenExactPayloadAndRetryReusesTheSameKey() = runTest {
        val store = MemoryMetadataStore()
        val gateway = FakeGateway().apply {
            submitResults += TemperatureSubmitResult.UnknownOutcome
            submitResults += TemperatureSubmitResult.Confirmed(facts(status = "UNKNOWN"))
        }
        val viewModel = viewModel(gateway, store)
        viewModel.activate(authority())
        advanceUntilIdle()
        val subject = viewModel.state.value.subjects.single()
        viewModel.selectSubject(subject)
        viewModel.valueChanged("4.2500")
        viewModel.occurredAtChanged("2026-09-30T15:22:33Z")
        viewModel.stageAndRecord()
        advanceUntilIdle()

        assertEquals(TemperatureCommandStatus.UnknownOutcome, viewModel.state.value.command)
        val frozen = requireNotNull(store.intent)
        assertEquals(TemperatureIntentStatus.UnknownOutcome, frozen.status)
        viewModel.valueChanged("100")
        viewModel.retryUnknownOutcome()
        advanceUntilIdle()

        assertEquals(
            listOf("temperature-key-1", "temperature-key-1"),
            gateway.commands.map {
                it.second
            }
        )
        assertEquals("4.2500", gateway.commands.last().first.value)
        assertEquals("UNKNOWN", viewModel.state.value.confirmed?.status)
        assertEquals(TemperatureCommandStatus.Confirmed, viewModel.state.value.command)
        assertNull(store.intent)
    }

    @Test
    fun restoredPendingBecomesUnknownAndIsNeverSentAutomatically() = runTest {
        val scope = authority().scope
        val store = MemoryMetadataStore().apply {
            intent = TemperatureEvidenceIntent(
                scope,
                "restored-key",
                payload(),
                TemperatureIntentStatus.Pending
            )
        }
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway, store)

        viewModel.activate(authority())
        advanceUntilIdle()

        assertEquals(TemperatureCommandStatus.UnknownOutcome, viewModel.state.value.command)
        assertEquals(TemperatureIntentStatus.UnknownOutcome, store.intent?.status)
        assertTrue(gateway.commands.isEmpty())
        assertEquals("restored-key", store.intent?.idempotencyKey)
        assertEquals("-18.765432100", store.intent?.payload?.value)
    }

    @Test
    fun missingPermissionOrUnavailableMetadataNeverDispatchesAndLateResponseIsDiscarded() =
        runTest {
            val noPermissionGateway = FakeGateway()
            val noPermission = viewModel(noPermissionGateway, MemoryMetadataStore())
            noPermission.activate(authority(permissions = setOf("warehouse.read")))
            advanceUntilIdle()
            fill(noPermission)
            noPermission.stageAndRecord()
            advanceUntilIdle()
            assertTrue(noPermissionGateway.commands.isEmpty())

            val unavailableStore = MemoryMetadataStore().apply { available = false }
            val unavailableGateway = FakeGateway()
            val unavailable = viewModel(unavailableGateway, unavailableStore)
            unavailable.activate(authority())
            advanceUntilIdle()
            fill(unavailable)
            unavailable.stageAndRecord()
            advanceUntilIdle()
            assertTrue(unavailableGateway.commands.isEmpty())

            val response = CompletableDeferred<TemperatureSubmitResult>()
            val delayedGateway = FakeGateway().apply { pendingResponse = response }
            val delayed = viewModel(delayedGateway, MemoryMetadataStore())
            delayed.activate(authority())
            advanceUntilIdle()
            fill(delayed)
            delayed.stageAndRecord()
            advanceUntilIdle()
            delayed.deactivate()
            response.complete(TemperatureSubmitResult.Confirmed(facts(status = "WITHIN_RANGE")))
            advanceUntilIdle()
            assertNull(delayed.state.value.confirmed)
            assertEquals(0, delayed.state.value.authorityEpoch)
        }

    private fun viewModel(gateway: FakeGateway, store: MemoryMetadataStore) =
        TemperatureEvidenceViewModel(
            gateway,
            store,
            now = { Instant.parse("2026-09-30T15:22:33Z") },
            newIdempotencyKey = { "temperature-key-1" }
        )

    private fun fill(viewModel: TemperatureEvidenceViewModel) {
        viewModel.subjectIdChanged(LOT_ID)
        viewModel.valueChanged("-18.765")
        viewModel.occurredAtChanged("2026-09-30T15:22:33Z")
    }

    private fun authority(permissions: Set<String> = setOf("inventory.receive", "inventory.read")) =
        TemperatureEvidenceAuthority(
            USER_ID,
            TENANT_ID,
            WORKSPACE_ID,
            MEMBERSHIP_ID,
            permissions,
            authorityEpoch = 9
        )

    private fun payload() = TemperatureEvidencePayload(
        TemperatureEvidenceSubjectType.LOT,
        LOT_ID,
        "-18.765432100",
        TemperatureEvidenceUnit.CELSIUS,
        "2026-09-30T15:22:33Z"
    )

    private fun facts(status: String) = TemperatureEvidenceFacts(
        id = EVIDENCE_ID,
        subjectType = TemperatureEvidenceSubjectType.LOT,
        subjectId = LOT_ID,
        lotId = LOT_ID,
        warehouseId = WAREHOUSE_ID,
        value = BigDecimal("-18.765432100"),
        unit = TemperatureEvidenceUnit.CELSIUS,
        occurredAt = Instant.parse("2026-09-30T15:22:33Z"),
        actorMembershipId = MEMBERSHIP_ID,
        status = status,
        source = "MANUAL"
    )

    private inner class FakeGateway : TemperatureEvidenceGateway {
        val subjectCalls = mutableListOf<TemperatureEvidenceSubjectType>()
        val commands = mutableListOf<Pair<TemperatureEvidencePayload, String>>()
        val submitResults = mutableListOf<TemperatureSubmitResult>()
        var lifecycleEvents: MutableList<String> = mutableListOf()
        var pendingResponse: CompletableDeferred<TemperatureSubmitResult>? = null

        override suspend fun subjects(
            type: TemperatureEvidenceSubjectType,
            authority: TemperatureEvidenceAuthority
        ): TemperatureLookupResult {
            subjectCalls += type
            return TemperatureLookupResult.Subjects(
                listOf(
                    TemperatureEvidenceSubject(
                        LOT_ID,
                        type,
                        if (type ==
                            TemperatureEvidenceSubjectType.LOT
                        ) {
                            "LOT-A · ACTIVE"
                        } else {
                            "Cold store · WH-1"
                        },
                        "Warehouse $WAREHOUSE_ID"
                    )
                )
            )
        }

        override suspend fun record(
            payload: TemperatureEvidencePayload,
            idempotencyKey: String,
            authority: TemperatureEvidenceAuthority
        ): TemperatureSubmitResult {
            commands += payload to idempotencyKey
            lifecycleEvents += "request"
            pendingResponse?.let { return it.await() }
            return submitResults.removeFirstOrNull()
                ?: TemperatureSubmitResult.Confirmed(facts(status = "WITHIN_RANGE"))
        }
    }

    private class MemoryMetadataStore : TemperatureEvidenceMetadataStore {
        var available = true
        var draft: TemperatureEvidenceDraft? = null
        var intent: TemperatureEvidenceIntent? = null
        val events = mutableListOf<String>()

        override suspend fun loadDraft(
            scope: TemperatureEvidenceScope
        ): TemperatureMetadataRead<TemperatureEvidenceDraft> = if (available) {
            TemperatureMetadataRead.Available(draft)
        } else {
            TemperatureMetadataRead.Unavailable
        }

        override suspend fun saveDraft(
            scope: TemperatureEvidenceScope,
            draft: TemperatureEvidenceDraft
        ): TemperatureMetadataWrite {
            if (!available) return TemperatureMetadataWrite.Unavailable
            this.draft = draft
            return TemperatureMetadataWrite.Saved
        }

        override suspend fun loadIntent(
            scope: TemperatureEvidenceScope
        ): TemperatureMetadataRead<TemperatureEvidenceIntent> = if (available) {
            TemperatureMetadataRead.Available(intent?.takeIf { it.scope == scope })
        } else {
            TemperatureMetadataRead.Unavailable
        }

        override suspend fun saveIntent(
            intent: TemperatureEvidenceIntent
        ): TemperatureMetadataWrite {
            if (!available) return TemperatureMetadataWrite.Unavailable
            val existing = this.intent
            if (existing != null && (
                    existing.idempotencyKey != intent.idempotencyKey ||
                        existing.payload != intent.payload
                    )
            ) {
                return TemperatureMetadataWrite.Unavailable
            }
            this.intent = intent
            events += "intent"
            return TemperatureMetadataWrite.Saved
        }

        override suspend fun markUnknownOutcome(
            scope: TemperatureEvidenceScope,
            idempotencyKey: String
        ): TemperatureMetadataWrite {
            if (!available) return TemperatureMetadataWrite.Unavailable
            val current =
                intent?.takeIf { it.scope == scope && it.idempotencyKey == idempotencyKey }
                    ?: return TemperatureMetadataWrite.Unavailable
            intent = current.copy(status = TemperatureIntentStatus.UnknownOutcome)
            return TemperatureMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: TemperatureEvidenceScope,
            idempotencyKey: String
        ): TemperatureMetadataWrite {
            if (!available) return TemperatureMetadataWrite.Unavailable
            if (intent?.idempotencyKey !=
                idempotencyKey
            ) {
                return TemperatureMetadataWrite.Unavailable
            }
            intent = null
            events += "clear"
            return TemperatureMetadataWrite.Saved
        }
    }

    private companion object {
        const val USER_ID = "e6c14000-0479-453f-93b9-c71cde8fbd01"
        const val TENANT_ID = "e6c14000-0479-453f-93b9-c71cde8fbd02"
        const val WORKSPACE_ID = "e6c14000-0479-453f-93b9-c71cde8fbd03"
        const val MEMBERSHIP_ID = "e6c14000-0479-453f-93b9-c71cde8fbd04"
        const val LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413401"
        const val WAREHOUSE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val EVIDENCE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413501"
    }
}
