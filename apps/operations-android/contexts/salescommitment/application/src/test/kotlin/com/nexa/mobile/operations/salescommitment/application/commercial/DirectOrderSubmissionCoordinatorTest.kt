package com.nexa.mobile.operations.salescommitment.application.commercial

import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderIntent
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderReceipt
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderRecord
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderDraft
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectOrderSubmissionCoordinatorTest {
    private val authority =
        CommercialAuthority("user", "tenant", "workspace", "member", emptySet(), 1)
    private val record = DirectOrderRecord(
        draft = DirectOrderDraft(customerId = "customer"),
        intent = DirectOrderIntent("intent-key", "frozen-request-body")
    )

    @Test
    fun persistsFrozenIntentBeforeTransportAndThenPersistsResolution() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingStore(events)
        val gateway = RecordingGateway(events) { intent ->
            assertEquals(record, store.records.single())
            assertEquals(record.intent, intent)
            DirectOrderSubmission.Confirmed(
                DirectOrderReceipt(
                    "request-42",
                    "SO-42",
                    "CONFIRMED",
                    "CASH_ON_DELIVERY",
                    "PEN",
                    "25.00",
                    1
                )
            )
        }

        val execution = DirectOrderSubmissionCoordinator(gateway, store).execute(
            authority,
            record
        ) { true }

        assertEquals(listOf("save", "submit", "save"), events)
        assertEquals(
            DirectOrderExecution.Resolved(
                record.copy(
                    intent = record.intent!!.copy(
                        outcome = "Confirmed",
                        receiptId = "request-42",
                        receipt = DirectOrderReceipt(
                            "request-42",
                            "SO-42",
                            "CONFIRMED",
                            "CASH_ON_DELIVERY",
                            "PEN",
                            "25.00",
                            1
                        )
                    )
                ),
                persisted = true
            ),
            execution
        )
        assertEquals("frozen-request-body", gateway.submitted.single().exactBody)
        assertEquals("Confirmed", store.records.last().intent?.outcome)
    }

    @Test
    fun prepaidPendingPersistsItsReceiptWithoutBecomingConfirmed() = runTest {
        val receipt = DirectOrderReceipt(
            "order-44",
            "SO-44",
            "PENDING",
            "PREPAID",
            "PEN",
            "25.00",
            0
        )
        val store = RecordingStore()
        val gateway = RecordingGateway {
            DirectOrderSubmission.PrepaidPending(receipt)
        }

        val execution = DirectOrderSubmissionCoordinator(gateway, store).execute(
            authority,
            record
        ) { true }

        val resolved = record.copy(
            intent = record.intent!!.copy(
                outcome = "PrepaidPending",
                receiptId = receipt.id,
                receipt = receipt
            )
        )
        assertEquals(DirectOrderExecution.Resolved(resolved, persisted = true), execution)
        assertEquals("PrepaidPending", store.records.last().intent?.outcome)
        assertEquals(receipt, store.records.last().intent?.receipt)
        assertEquals(receipt.id, store.records.last().intent?.receiptId)
    }

    @Test
    fun blocksTransportWhenFrozenIntentCannotBePersisted() = runTest {
        val store = RecordingStore().apply { saveResults += false }
        val gateway = RecordingGateway()

        val execution = DirectOrderSubmissionCoordinator(gateway, store).execute(
            authority,
            record
        ) { true }

        assertEquals(DirectOrderExecution.MetadataUnavailable, execution)
        assertTrue(gateway.submitted.isEmpty())
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun keepsPendingIntentWhenContextExpiresAfterStaging() = runTest {
        val store = RecordingStore()
        val gateway = RecordingGateway()
        var contextChecks = 0

        val execution = DirectOrderSubmissionCoordinator(gateway, store).execute(
            authority,
            record
        ) { ++contextChecks == 1 }

        assertEquals(DirectOrderExecution.ContextLost, execution)
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
            DirectOrderSubmission.Confirmed(
                DirectOrderReceipt(
                    "request-43",
                    "SO-42",
                    "CONFIRMED",
                    "CASH_ON_DELIVERY",
                    "PEN",
                    "25.00",
                    1
                )
            )
        }

        val execution = DirectOrderSubmissionCoordinator(gateway, store).execute(
            authority,
            record
        ) {
            contextChecks++
            contextIsCurrent
        }

        assertEquals(1, gateway.submitted.size)
        assertEquals(2, contextChecks)
        assertEquals(
            DirectOrderExecution.Resolved(
                record.copy(
                    intent = record.intent!!.copy(
                        outcome = "Confirmed",
                        receiptId = "request-43",
                        receipt = DirectOrderReceipt(
                            "request-43",
                            "SO-42",
                            "CONFIRMED",
                            "CASH_ON_DELIVERY",
                            "PEN",
                            "25.00",
                            1
                        )
                    )
                ),
                persisted = true
            ),
            execution
        )
        assertEquals("Confirmed", store.records.last().intent?.outcome)
        assertFalse(contextIsCurrent)
    }

    private class RecordingStore(private val events: MutableList<String> = mutableListOf()) :
        DirectOrderStore {
        val records = mutableListOf<DirectOrderRecord>()
        val saveResults = mutableListOf<Boolean>()

        override suspend fun load(authority: CommercialAuthority): DirectOrderRead =
            records.lastOrNull()?.let(DirectOrderRead::Available) ?: DirectOrderRead.Unavailable

        override suspend fun save(
            authority: CommercialAuthority,
            record: DirectOrderRecord
        ): Boolean {
            events += "save"
            val saved = if (saveResults.isEmpty()) true else saveResults.removeAt(0)
            if (saved) records += record
            return saved
        }
    }

    private class RecordingGateway(
        private val events: MutableList<String> = mutableListOf(),
        private val submitResult: suspend (DirectOrderIntent) -> DirectOrderSubmission = {
            DirectOrderSubmission.UnknownOutcome
        }
    ) : DirectOrderGateway {
        val submitted = mutableListOf<DirectOrderIntent>()

        override suspend fun quote(
            authority: CommercialAuthority,
            customerId: String,
            productId: String,
            quantity: String
        ) = null

        override suspend fun review(authority: CommercialAuthority, draft: DirectOrderDraft) =
            DirectOrderReview.Unavailable

        override fun freeze(draft: DirectOrderDraft) = "frozen-request-body"

        override suspend fun submit(
            authority: CommercialAuthority,
            intent: DirectOrderIntent
        ): DirectOrderSubmission {
            events += "submit"
            submitted += intent
            return submitResult(intent)
        }
    }
}
