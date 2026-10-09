package com.nexa.mobile.operations.salescommitment.presentation.commercial

import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderRead
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderReview
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderStore
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderSubmission
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderIntent
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderReceipt
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderRecord
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderDraft
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderLine
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectOrderViewModelTest {
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
        DirectOrderDraft(
            "customer",
            2,
            listOf(
                DirectOrderLine("CAT-1", "Product", "2", "KG", "30", "PEN", "2026-09-30T00:00:00Z")
            ),
            "2026-10-01",
            "Door"
        )
    private class Store(var record: DirectOrderRecord, var saveAllowed: Boolean = true) :
        DirectOrderStore {
        override suspend fun load(authority: CommercialAuthority): DirectOrderRead {
            if (record.intent?.outcome ==
                "Pending"
            ) {
                record =
                    record.copy(intent = record.intent!!.copy(outcome = "UnknownOutcome"))
            }
            return DirectOrderRead.Available(record)
        }
        override suspend fun save(
            authority: CommercialAuthority,
            record: DirectOrderRecord
        ): Boolean {
            if (!saveAllowed) return false
            this.record = record
            return true
        }
    }
    private open class Gateway : DirectOrderGateway {
        val sent = mutableListOf<DirectOrderIntent>()
        override suspend fun quote(
            authority: CommercialAuthority,
            customerId: String,
            productId: String,
            quantity: String
        ): DirectOrderLine? = null
        override suspend fun review(authority: CommercialAuthority, draft: DirectOrderDraft) =
            DirectOrderReview.Current(draft)
        override fun freeze(draft: DirectOrderDraft) = "exact-original-body"
        override suspend fun submit(
            authority: CommercialAuthority,
            intent: DirectOrderIntent
        ): DirectOrderSubmission {
            sent += intent
            return DirectOrderSubmission.UnknownOutcome
        }
    }

    @Test fun storageFailureBlocksPost() = runTest {
        val store = Store(DirectOrderRecord(draft))
        val gateway = Gateway()
        val model = DirectOrderViewModel(gateway, store)
        model.activate(authority)
        runCurrent()
        model.review()
        runCurrent()
        store.saveAllowed = false
        model.submit()
        runCurrent()
        assertTrue(gateway.sent.isEmpty())
        assertEquals(DirectOrderStatus.MetadataUnavailable, model.state.value.status)
    }

    @Test fun reconstructionAndManualReplayPreserveOriginalBodyAndKey() = runTest {
        val store = Store(DirectOrderRecord(draft))
        val gateway = Gateway()
        val model = DirectOrderViewModel(gateway, store)
        model.activate(authority)
        runCurrent()
        model.review()
        runCurrent()
        model.submit()
        runCurrent()
        val first = gateway.sent.single()
        model.deactivate()
        val recovered = DirectOrderViewModel(gateway, store)
        recovered.activate(authority)
        runCurrent()
        assertEquals(DirectOrderStatus.UnknownOutcome, recovered.state.value.status)
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

    @Test fun recoveredTerminalIntentsAllowNewDecisionButUnknownOutcomeStaysProtected() = runTest {
        listOf(
            "PermissionDenied" to DirectOrderStatus.PermissionDenied,
            "Unavailable" to DirectOrderStatus.Unavailable
        ).forEach { (outcome, expectedStatus) ->
            val priorIntent = DirectOrderIntent(
                key = "terminal-$outcome",
                exactBody = "terminal-body",
                outcome = outcome
            )
            val store = Store(DirectOrderRecord(draft, priorIntent))
            val model = DirectOrderViewModel(Gateway(), store)

            model.activate(authority)
            runCurrent()

            assertEquals(expectedStatus, model.state.value.status)
            assertTrue(model.state.value.status.permitsNewDecision)

            model.startNewDecision()
            runCurrent()

            assertEquals(DirectOrderStatus.Draft, model.state.value.status)
            assertEquals(draft, model.state.value.record.draft)
            assertEquals(null, model.state.value.record.intent)
            assertEquals(null, store.record.intent)
        }

        val unknownIntent = DirectOrderIntent(
            key = "unknown-key",
            exactBody = "unknown-original-body",
            outcome = "UnknownOutcome"
        )
        val unknownStore = Store(DirectOrderRecord(draft, unknownIntent))
        val unknownGateway = Gateway()
        val unknownModel = DirectOrderViewModel(unknownGateway, unknownStore)

        unknownModel.activate(authority)
        runCurrent()
        assertEquals(DirectOrderStatus.UnknownOutcome, unknownModel.state.value.status)
        assertFalse(unknownModel.state.value.status.permitsNewDecision)

        unknownModel.startNewDecision()
        unknownModel.customerChanged("different-customer")
        unknownModel.submit()
        runCurrent()

        assertEquals(DirectOrderStatus.UnknownOutcome, unknownModel.state.value.status)
        assertEquals(unknownIntent, unknownStore.record.intent)
        assertTrue(unknownGateway.sent.isEmpty())

        unknownModel.retryUnknownOutcome()
        runCurrent()

        assertEquals(unknownIntent.key, unknownGateway.sent.single().key)
        assertEquals(unknownIntent.exactBody, unknownGateway.sent.single().exactBody)
    }

    @Test fun confirmedAndPrepaidPendingKeepDistinctOutcomesAndReceipts() = runTest {
        suspend fun submit(result: DirectOrderSubmission): Pair<DirectOrderViewModel, Store> {
            val store = Store(DirectOrderRecord(draft))
            val gateway = object : Gateway() {
                override suspend fun submit(
                    authority: CommercialAuthority,
                    intent: DirectOrderIntent
                ): DirectOrderSubmission {
                    sent += intent
                    return result
                }
            }
            val model = DirectOrderViewModel(gateway, store)
            model.activate(authority)
            runCurrent()
            model.review()
            runCurrent()
            model.submit()
            runCurrent()
            return model to store
        }

        val confirmedReceipt =
            DirectOrderReceipt(
                "order-confirmed",
                "SO-45",
                "CONFIRMED",
                "CASH_ON_DELIVERY",
                "PEN",
                "25.00",
                1
            )
        val (confirmed, confirmedStore) = submit(DirectOrderSubmission.Confirmed(confirmedReceipt))
        assertEquals(DirectOrderStatus.Confirmed, confirmed.state.value.status)
        assertEquals("Confirmed", confirmedStore.record.intent?.outcome)
        assertEquals(confirmedReceipt, confirmedStore.record.intent?.receipt)

        val pendingReceipt =
            DirectOrderReceipt(
                "order-pending",
                "SO-46",
                "PENDING",
                "PREPAID",
                "PEN",
                "25.00",
                0
            )
        val (pending, pendingStore) = submit(DirectOrderSubmission.PrepaidPending(pendingReceipt))
        assertEquals(DirectOrderStatus.PrepaidPending, pending.state.value.status)
        assertEquals("PrepaidPending", pendingStore.record.intent?.outcome)
        assertEquals(pendingReceipt, pendingStore.record.intent?.receipt)
        assertEquals("PENDING", pendingStore.record.intent?.receipt?.status)
    }

    @Test fun legacyPurchaseRequestIntentIsRetainedAndCannotBeRetriedAsDirectOrder() = runTest {
        val legacyIntent = DirectOrderIntent(
            key = "legacy-key",
            exactBody = "legacy-purchase-request-body",
            operation = DirectOrderIntent.LEGACY_FIELD_REQUEST_OPERATION
        )
        val store = Store(DirectOrderRecord(draft, legacyIntent))
        val gateway = Gateway()
        val model = DirectOrderViewModel(gateway, store)

        model.activate(authority)
        runCurrent()
        model.submit()
        model.retryUnknownOutcome()
        model.startNewDecision()
        model.customerChanged("different-customer")
        runCurrent()

        assertEquals(DirectOrderStatus.LegacyIntent, model.state.value.status)
        assertEquals(legacyIntent.copy(outcome = "UnknownOutcome"), store.record.intent)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun responseAfterRouteLossCannotRestoreConfirmationButPersistsResolution() = runTest {
        val pending = CompletableDeferred<DirectOrderSubmission>()
        val gateway = object : Gateway() {
            override suspend fun submit(
                authority: CommercialAuthority,
                intent: DirectOrderIntent
            ): DirectOrderSubmission {
                sent += intent
                return pending.await()
            }
        }
        val store = Store(DirectOrderRecord(draft))
        val model = DirectOrderViewModel(gateway, store)
        model.activate(authority)
        runCurrent()
        model.review()
        runCurrent()
        model.submit()
        runCurrent()
        assertEquals("Pending", store.record.intent?.outcome)
        model.deactivate()
        pending.complete(
            DirectOrderSubmission.Confirmed(
                DirectOrderReceipt(
                    "request",
                    "SO-42",
                    "CONFIRMED",
                    "CASH_ON_DELIVERY",
                    "PEN",
                    "25.00",
                    1
                )
            )
        )
        runCurrent()
        assertEquals(DirectOrderStatus.Loading, model.state.value.status)
        assertEquals("Confirmed", store.record.intent?.outcome)
        model.activate(authority)
        runCurrent()
        assertEquals(DirectOrderStatus.Confirmed, model.state.value.status)
    }

    @Test fun changedPriceRequiresExplicitAcceptanceAndOfflineEditInvalidatesReview() = runTest {
        val store = Store(DirectOrderRecord(draft))
        val gateway = object : Gateway() {
            override suspend fun review(authority: CommercialAuthority, draft: DirectOrderDraft) =
                DirectOrderReview.Current(
                    draft.copy(
                        lines = draft.lines.map {
                            it.copy(price = "40")
                        }
                    )
                )
        }
        val model = DirectOrderViewModel(gateway, store)
        model.activate(authority)
        runCurrent()
        model.review()
        runCurrent()
        assertEquals(DirectOrderStatus.Changed, model.state.value.status)
        model.submit()
        runCurrent()
        assertTrue(gateway.sent.isEmpty())
        model.acceptChangedInformation()
        model.lineQuantityChanged("CAT-1", "3")
        runCurrent()
        assertEquals(DirectOrderStatus.Draft, model.state.value.status)
        assertEquals("3", store.record.draft.lines.single().quantity)
        model.submit()
        runCurrent()
        assertTrue(gateway.sent.isEmpty())
    }
}
