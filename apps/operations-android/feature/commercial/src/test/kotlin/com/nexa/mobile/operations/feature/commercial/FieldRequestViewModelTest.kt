package com.nexa.mobile.operations.feature.commercial

import com.nexa.mobile.operations.feature.commercial.application.FieldRequestGateway
import com.nexa.mobile.operations.feature.commercial.application.FieldRequestRead
import com.nexa.mobile.operations.feature.commercial.application.FieldRequestReview
import com.nexa.mobile.operations.feature.commercial.application.FieldRequestStore
import com.nexa.mobile.operations.feature.commercial.application.FieldRequestSubmission
import com.nexa.mobile.operations.feature.commercial.model.CommercialAuthority
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestDraft
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestIntent
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestLine
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FieldRequestViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val authority =
        CommercialAuthority(
            "u",
            "t",
            "w",
            "m",
            setOf("client.read", "catalog.read", "sales:write"),
            7
        )
    private val draft =
        FieldRequestDraft(
            "customer",
            2,
            listOf(
                FieldRequestLine("CAT-1", "Product", "2", "KG", "30", "PEN", "2026-09-30T00:00:00Z")
            ),
            "2026-10-01",
            "Door"
        )
    private class Store(var record: FieldRequestRecord, var saveAllowed: Boolean = true) :
        FieldRequestStore {
        override suspend fun load(authority: CommercialAuthority): FieldRequestRead {
            if (record.intent?.outcome ==
                "Pending"
            ) {
                record =
                    record.copy(intent = record.intent!!.copy(outcome = "UnknownOutcome"))
            }
            return FieldRequestRead.Available(record)
        }
        override suspend fun save(
            authority: CommercialAuthority,
            record: FieldRequestRecord
        ): Boolean {
            if (!saveAllowed) return false
            this.record = record
            return true
        }
    }
    private open class Gateway : FieldRequestGateway {
        val sent = mutableListOf<FieldRequestIntent>()
        override suspend fun quote(
            authority: CommercialAuthority,
            customerId: String,
            productId: String,
            quantity: String
        ): FieldRequestLine? = null
        override suspend fun review(authority: CommercialAuthority, draft: FieldRequestDraft) =
            FieldRequestReview.Current(draft)
        override fun freeze(draft: FieldRequestDraft) = "exact-original-body"
        override suspend fun submit(
            authority: CommercialAuthority,
            intent: FieldRequestIntent
        ): FieldRequestSubmission {
            sent += intent
            return FieldRequestSubmission.UnknownOutcome
        }
    }

    @Test fun storageFailureBlocksPost() = runTest {
        val store = Store(FieldRequestRecord(draft))
        val gateway = Gateway()
        val model = FieldRequestViewModel(gateway, store)
        model.activate(authority)
        runCurrent()
        model.review()
        runCurrent()
        store.saveAllowed = false
        model.submit()
        runCurrent()
        assertTrue(gateway.sent.isEmpty())
        assertEquals(FieldRequestStatus.MetadataUnavailable, model.state.value.status)
    }

    @Test fun reconstructionAndManualReplayPreserveOriginalBodyAndKey() = runTest {
        val store = Store(FieldRequestRecord(draft))
        val gateway = Gateway()
        val model = FieldRequestViewModel(gateway, store)
        model.activate(authority)
        runCurrent()
        model.review()
        runCurrent()
        model.submit()
        runCurrent()
        val first = gateway.sent.single()
        model.deactivate()
        val recovered = FieldRequestViewModel(gateway, store)
        recovered.activate(authority)
        runCurrent()
        assertEquals(FieldRequestStatus.UnknownOutcome, recovered.state.value.status)
        recovered.customerChanged("other")
        recovered.lineQuantityChanged("CAT-1", "999")
        recovered.submit()
        runCurrent()
        assertEquals(1, gateway.sent.size)
        recovered.retryUnknownOutcome()
        runCurrent()
        assertEquals(first.key, gateway.sent.last().key)
        assertEquals(first.exactBody, gateway.sent.last().exactBody)
    }

    @Test fun responseAfterRouteLossCannotRestoreConfirmationButPersistsResolution() = runTest {
        val pending = CompletableDeferred<FieldRequestSubmission>()
        val gateway = object : Gateway() {
            override suspend fun submit(
                authority: CommercialAuthority,
                intent: FieldRequestIntent
            ): FieldRequestSubmission {
                sent += intent
                return pending.await()
            }
        }
        val store = Store(FieldRequestRecord(draft))
        val model = FieldRequestViewModel(gateway, store)
        model.activate(authority)
        runCurrent()
        model.review()
        runCurrent()
        model.submit()
        runCurrent()
        assertEquals("Pending", store.record.intent?.outcome)
        model.deactivate()
        pending.complete(FieldRequestSubmission.Confirmed("request"))
        runCurrent()
        assertEquals(FieldRequestStatus.Loading, model.state.value.status)
        assertEquals("Confirmed", store.record.intent?.outcome)
        model.activate(authority)
        runCurrent()
        assertEquals(FieldRequestStatus.Confirmed, model.state.value.status)
    }

    @Test fun changedPriceRequiresExplicitAcceptanceAndOfflineEditInvalidatesReview() = runTest {
        val store = Store(FieldRequestRecord(draft))
        val gateway = object : Gateway() {
            override suspend fun review(authority: CommercialAuthority, draft: FieldRequestDraft) =
                FieldRequestReview.Current(
                    draft.copy(
                        lines = draft.lines.map {
                            it.copy(price = "40")
                        }
                    )
                )
        }
        val model = FieldRequestViewModel(gateway, store)
        model.activate(authority)
        runCurrent()
        model.review()
        runCurrent()
        assertEquals(FieldRequestStatus.Changed, model.state.value.status)
        model.submit()
        runCurrent()
        assertTrue(gateway.sent.isEmpty())
        model.acceptChangedInformation()
        model.lineQuantityChanged("CAT-1", "3")
        runCurrent()
        assertEquals(FieldRequestStatus.Draft, model.state.value.status)
        assertEquals("3", store.record.draft.lines.single().quantity)
        model.submit()
        runCurrent()
        assertTrue(gateway.sent.isEmpty())
    }
}
