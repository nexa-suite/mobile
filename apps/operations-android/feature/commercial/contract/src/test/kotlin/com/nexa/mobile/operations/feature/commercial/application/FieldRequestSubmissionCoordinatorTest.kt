package com.nexa.mobile.operations.feature.commercial.application

import com.nexa.mobile.operations.feature.commercial.model.CommercialAuthority
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestDraft
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestIntent
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldRequestSubmissionCoordinatorTest {
    private val authority =
        CommercialAuthority("user", "tenant", "workspace", "member", emptySet(), 1)
    private val record = FieldRequestRecord(
        draft = FieldRequestDraft(customerId = "customer"),
        intent = FieldRequestIntent("intent-key", "frozen-request-body")
    )

    @Test
    fun persistsFrozenIntentBeforeTransportAndThenPersistsResolution() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingStore(events)
        val gateway = RecordingGateway(events) { intent ->
            assertEquals(record, store.records.single())
            assertEquals(record.intent, intent)
            FieldRequestSubmission.Confirmed("request-42")
        }

        val execution = FieldRequestSubmissionCoordinator(gateway, store).execute(
            authority,
            record
        ) { true }

        assertEquals(listOf("save", "submit", "save"), events)
        assertEquals(
            FieldRequestExecution.Resolved(
                record.copy(
                    intent = record.intent!!.copy(outcome = "Confirmed", receiptId = "request-42")
                ),
                persisted = true
            ),
            execution
        )
        assertEquals("frozen-request-body", gateway.submitted.single().exactBody)
        assertEquals("Confirmed", store.records.last().intent?.outcome)
    }

    @Test
    fun blocksTransportWhenFrozenIntentCannotBePersisted() = runTest {
        val store = RecordingStore().apply { saveResults += false }
        val gateway = RecordingGateway()

        val execution = FieldRequestSubmissionCoordinator(gateway, store).execute(
            authority,
            record
        ) { true }

        assertEquals(FieldRequestExecution.MetadataUnavailable, execution)
        assertTrue(gateway.submitted.isEmpty())
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun keepsPendingIntentWhenContextExpiresAfterStaging() = runTest {
        val store = RecordingStore()
        val gateway = RecordingGateway()
        var contextChecks = 0

        val execution = FieldRequestSubmissionCoordinator(gateway, store).execute(
            authority,
            record
        ) { ++contextChecks == 1 }

        assertEquals(FieldRequestExecution.ContextLost, execution)
        assertEquals(2, contextChecks)
        assertEquals(listOf(record), store.records)
        assertTrue(gateway.submitted.isEmpty())
    }

    @Test
    fun persistsResolutionEvenWhenContextIsLostDuringTransport() = runTest {
        val store = RecordingStore()
        var contextIsCurrent = true
        var contextChecks = 0
        val gateway = RecordingGateway {
            contextIsCurrent = false
            FieldRequestSubmission.Confirmed("request-43")
        }

        val execution = FieldRequestSubmissionCoordinator(gateway, store).execute(
            authority,
            record
        ) {
            contextChecks++
            contextIsCurrent
        }

        assertEquals(1, gateway.submitted.size)
        assertEquals(2, contextChecks)
        assertEquals(
            FieldRequestExecution.Resolved(
                record.copy(
                    intent = record.intent!!.copy(outcome = "Confirmed", receiptId = "request-43")
                ),
                persisted = true
            ),
            execution
        )
        assertEquals("Confirmed", store.records.last().intent?.outcome)
        assertFalse(contextIsCurrent)
    }

    private class RecordingStore(private val events: MutableList<String> = mutableListOf()) :
        FieldRequestStore {
        val records = mutableListOf<FieldRequestRecord>()
        val saveResults = mutableListOf<Boolean>()

        override suspend fun load(authority: CommercialAuthority): FieldRequestRead =
            records.lastOrNull()?.let(FieldRequestRead::Available) ?: FieldRequestRead.Unavailable

        override suspend fun save(
            authority: CommercialAuthority,
            record: FieldRequestRecord
        ): Boolean {
            events += "save"
            val saved = if (saveResults.isEmpty()) true else saveResults.removeAt(0)
            if (saved) records += record
            return saved
        }
    }

    private class RecordingGateway(
        private val events: MutableList<String> = mutableListOf(),
        private val submitResult: suspend (FieldRequestIntent) -> FieldRequestSubmission = {
            FieldRequestSubmission.UnknownOutcome
        }
    ) : FieldRequestGateway {
        val submitted = mutableListOf<FieldRequestIntent>()

        override suspend fun quote(
            authority: CommercialAuthority,
            customerId: String,
            productId: String,
            quantity: String
        ) = null

        override suspend fun review(authority: CommercialAuthority, draft: FieldRequestDraft) =
            FieldRequestReview.Unavailable

        override fun freeze(draft: FieldRequestDraft) = "frozen-request-body"

        override suspend fun submit(
            authority: CommercialAuthority,
            intent: FieldRequestIntent
        ): FieldRequestSubmission {
            events += "submit"
            submitted += intent
            return submitResult(intent)
        }
    }
}
