package com.nexa.mobile.operations.payments.presentation.refund

import com.nexa.mobile.operations.payments.application.refund.WalletRefundAuthority
import com.nexa.mobile.operations.payments.application.refund.WalletRefundCandidatesRead
import com.nexa.mobile.operations.payments.application.refund.WalletRefundGateway
import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntent
import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntentStage
import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntentStore
import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntentsRead
import com.nexa.mobile.operations.payments.application.refund.WalletRefundSubmission
import com.nexa.mobile.operations.payments.domain.model.WalletRefundCandidate
import com.nexa.mobile.operations.payments.domain.model.WalletRefundCandidatePage
import com.nexa.mobile.operations.payments.domain.model.WalletRefundPosted
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.VerifiedOperationsIdentity
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalletRefundViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test
    fun responseDuringRefreshIsNotAppliedAfterContextDeactivation() = runTest {
        val gateway = FakeGateway()
        val store = FakeStore()
        val model = WalletRefundViewModel(gateway, store)
        model.activate(authority)
        runCurrent()

        model.selectOrder(candidate.salesOrderId)
        model.amountChanged("1.2500")
        model.reasonChanged("Solicitud del cliente")
        val refreshGate = CompletableDeferred<Unit>()
        gateway.nextCandidateRead = refreshGate
        model.submit()
        runCurrent()

        model.deactivate()
        refreshGate.complete(Unit)
        runCurrent()

        assertNull(model.state.value.lastPosted)
    }

    private class FakeGateway : WalletRefundGateway {
        var nextCandidateRead: CompletableDeferred<Unit>? = null

        override fun freezeIntent(
            salesOrderId: String,
            amountPEN: String,
            reason: String
        ): WalletRefundIntent = WalletRefundIntent(
            salesOrderId = salesOrderId,
            amountPEN = amountPEN,
            reason = reason,
            idempotencyKey = "1ef849dd-4578-4b8b-a62e-54d3dfbd2cb1",
            payloadHash = "0".repeat(64),
            exactPayload = "{}"
        )

        override suspend fun candidates(
            authority: WalletRefundAuthority,
            page: Int,
            size: Int
        ): WalletRefundCandidatesRead {
            nextCandidateRead?.let { gate ->
                nextCandidateRead = null
                gate.await()
            }
            return WalletRefundCandidatesRead.Available(
                WalletRefundCandidatePage(listOf(candidate), page, size, 1)
            )
        }

        override suspend fun submit(authority: WalletRefundAuthority, intent: WalletRefundIntent) =
            WalletRefundSubmission.Posted(
                WalletRefundPosted(
                    intent.salesOrderId,
                    REFUND_ID,
                    LEDGER_ID,
                    BigDecimal(intent.amountPEN),
                    "PEN",
                    "POSTED"
                )
            )
    }

    private class FakeStore : WalletRefundIntentStore {
        private val intents = mutableListOf<WalletRefundIntent>()

        override suspend fun load(authority: WalletRefundAuthority) =
            WalletRefundIntentsRead.Available(intents.toList())

        override suspend fun stage(
            authority: WalletRefundAuthority,
            intent: WalletRefundIntent
        ): WalletRefundIntentStage {
            intents += intent
            return WalletRefundIntentStage.Stored
        }

        override suspend fun remove(
            authority: WalletRefundAuthority,
            intent: WalletRefundIntent
        ): Boolean = intents.remove(intent)
    }

    private companion object {
        const val REFUND_ID = "d48c9a7b-954e-4356-992d-bc6222ddfd4f"
        const val LEDGER_ID = "f4f8ca2c-9c1b-4429-92ed-5040d6292ced"
        val candidate = WalletRefundCandidate(
            salesOrderId = "1f7820ad-e752-469c-8e79-f1f0c32baead",
            orderNumber = "SO-42",
            currency = "PEN",
            consumedPEN = BigDecimal("5.0000"),
            refundedPEN = BigDecimal("0.0000"),
            refundablePEN = BigDecimal("1.2500")
        )
        val authority = WalletRefundAuthority(
            VerifiedOperationsIdentity(
                userId = "user",
                tenantId = "tenant",
                workspaceId = "workspace",
                membershipId = "membership",
                permissions = setOf("payment.reconcile"),
                roles = setOf("COMPANY_OWNER")
            ),
            authorityEpoch = 1
        )
    }
}
