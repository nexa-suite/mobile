package com.nexa.mobile.operations.payments.application.refund

import com.nexa.mobile.operations.payments.domain.model.WalletRefundCandidate
import com.nexa.mobile.operations.payments.domain.model.WalletRefundCandidatePage
import com.nexa.mobile.operations.payments.domain.model.WalletRefundPosted
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.VerifiedOperationsIdentity
import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletRefundCoordinatorTest {
    private val candidate = WalletRefundCandidate(
        salesOrderId = ORDER_ID,
        orderNumber = "SO-2026-0042",
        currency = "PEN",
        consumedPEN = BigDecimal("40.0000"),
        refundedPEN = BigDecimal("0.0000"),
        refundablePEN = BigDecimal("12.3456")
    )

    @Test
    fun ambiguousRetryReusesPersistedPayloadAndIdempotencyKey() = runTest {
        val store = FakeStore()
        val gateway = FakeGateway(
            mutableListOf(
                WalletRefundSubmission.UnknownOutcome,
                WalletRefundSubmission.Posted(
                    WalletRefundPosted(
                        ORDER_ID,
                        REFUND_ID,
                        LEDGER_ID,
                        BigDecimal("12.3456"),
                        "PEN",
                        "POSTED"
                    )
                )
            )
        )
        val coordinator = WalletRefundCoordinator(gateway, store)

        val first = coordinator.submitNew(
            authority,
            candidate,
            "12.3456",
            "Cliente solicitó devolución"
        ) { true }

        assertTrue(first is WalletRefundMutationResult.UnknownOutcome)
        val intent = store.pending.single()
        assertEquals("12.3456", intent.amountPEN)
        assertEquals(
            """{"amountPEN":12.3456,"reason":"Cliente solicitó devolución"}""",
            intent.exactPayload
        )
        assertEquals(1, gateway.submissions.size)

        val second = coordinator.retry(authority, intent) { true }
        assertTrue(second is WalletRefundMutationResult.Posted)
        assertEquals(2, gateway.submissions.size)
        assertEquals(gateway.submissions[0], gateway.submissions[1])
        assertEquals(emptyList<WalletRefundIntent>(), store.pending)
    }

    @Test
    fun unknownIntentBlocksAnotherCommandForSameOrder() = runTest {
        val store = FakeStore()
        val gateway = FakeGateway(mutableListOf(WalletRefundSubmission.UnknownOutcome))
        val coordinator = WalletRefundCoordinator(gateway, store)
        coordinator.submitNew(authority, candidate, "8.00", "Primera solicitud") { true }
        val previous = store.pending.single()

        val result = coordinator.submitNew(
            authority,
            candidate,
            "4.00",
            "Otra solicitud"
        ) { true }

        assertEquals(WalletRefundMutationResult.PendingIntent(previous), result)
        assertEquals(1, gateway.submissions.size)
        assertEquals(previous, store.pending.single())
    }

    @Test
    fun roleAndPermissionHintsFailClosedBeforeStorageOrNetwork() = runTest {
        val store = FakeStore()
        val gateway = FakeGateway(mutableListOf())
        val coordinator = WalletRefundCoordinator(gateway, store)

        val wrongRole = authority.copy(
            identity = authority.identity.copy(roles = setOf("SALES"))
        )
        val missingPermission = authority.copy(
            identity = authority.identity.copy(permissions = emptySet())
        )
        assertEquals(
            WalletRefundMutationResult.NotAuthorized,
            coordinator.submitNew(wrongRole, candidate, "1.00", "Motivo") { true }
        )
        assertEquals(
            WalletRefundMutationResult.NotAuthorized,
            coordinator.submitNew(missingPermission, candidate, "1.00", "Motivo") { true }
        )
        assertTrue(store.pending.isEmpty())
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun amountPrecisionBalanceAndRequiredReasonAreValidatedLocally() = runTest {
        val store = FakeStore()
        val gateway = FakeGateway(mutableListOf())
        val coordinator = WalletRefundCoordinator(gateway, store)

        assertEquals(
            WalletRefundMutationResult.InvalidAmount,
            coordinator.submitNew(authority, candidate, "1.00001", "Motivo") { true }
        )
        assertEquals(
            WalletRefundMutationResult.AmountExceedsServerBalance,
            coordinator.submitNew(authority, candidate, "12.3457", "Motivo") { true }
        )
        assertEquals(
            WalletRefundMutationResult.MissingReason,
            coordinator.submitNew(authority, candidate, "1.00", "  ") { true }
        )
        assertEquals(
            WalletRefundMutationResult.ReasonTooLong,
            coordinator.submitNew(authority, candidate, "1.00", "x".repeat(501)) { true }
        )
        assertTrue(store.pending.isEmpty())
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun contextLossAfterStagingNeverDispatchesMutation() = runTest {
        val store = FakeStore()
        val gateway = FakeGateway(mutableListOf())
        val coordinator = WalletRefundCoordinator(gateway, store)
        var checks = 0

        val result = coordinator.submitNew(
            authority,
            candidate,
            "1.2500",
            "Motivo"
        ) { ++checks == 1 }

        assertEquals(WalletRefundMutationResult.ContextLost, result)
        assertEquals(1, store.pending.size)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun confirmedResponseAfterContextReplacementIsNotReturnedToCaller() = runTest {
        val store = FakeStore()
        val gateway = FakeGateway(
            mutableListOf(
                WalletRefundSubmission.Posted(
                    WalletRefundPosted(
                        ORDER_ID,
                        REFUND_ID,
                        LEDGER_ID,
                        BigDecimal("1.2500"),
                        "PEN",
                        "POSTED"
                    )
                )
            )
        )
        val coordinator = WalletRefundCoordinator(gateway, store)
        var checks = 0

        val result = coordinator.submitNew(
            authority,
            candidate,
            "1.2500",
            "Motivo"
        ) { ++checks < 4 }

        assertEquals(WalletRefundMutationResult.ContextLost, result)
        assertEquals(1, gateway.submissions.size)
        assertTrue(store.pending.isEmpty())
    }

    private class FakeGateway(private val outcomes: MutableList<WalletRefundSubmission>) :
        WalletRefundGateway {
        val submissions = mutableListOf<WalletRefundIntent>()

        override fun freezeIntent(
            salesOrderId: String,
            amountPEN: String,
            reason: String
        ): WalletRefundIntent {
            val payload = "{\"amountPEN\":$amountPEN,\"reason\":\"$reason\"}"
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return WalletRefundIntent(
                salesOrderId = salesOrderId,
                amountPEN = amountPEN,
                reason = reason,
                idempotencyKey = "1ef849dd-4578-4b8b-a62e-54d3dfbd2cb1",
                payloadHash = hash,
                exactPayload = payload
            )
        }

        override suspend fun candidates(authority: WalletRefundAuthority, page: Int, size: Int) =
            WalletRefundCandidatesRead.Available(
                WalletRefundCandidatePage(emptyList(), page, size, 0)
            )

        override suspend fun submit(
            authority: WalletRefundAuthority,
            intent: WalletRefundIntent
        ): WalletRefundSubmission {
            submissions += intent
            return outcomes.removeAt(0)
        }
    }

    private class FakeStore : WalletRefundIntentStore {
        val pending = mutableListOf<WalletRefundIntent>()

        override suspend fun load(authority: WalletRefundAuthority) =
            WalletRefundIntentsRead.Available(pending.toList())

        override suspend fun stage(
            authority: WalletRefundAuthority,
            intent: WalletRefundIntent
        ): WalletRefundIntentStage {
            if (pending.any { it.salesOrderId == intent.salesOrderId }) {
                return WalletRefundIntentStage.SameOrderPending
            }
            pending += intent
            return WalletRefundIntentStage.Stored
        }

        override suspend fun remove(
            authority: WalletRefundAuthority,
            intent: WalletRefundIntent
        ): Boolean =
            pending.remove(intent) || pending.none { it.salesOrderId == intent.salesOrderId }
    }

    private companion object {
        const val ORDER_ID = "1f7820ad-e752-469c-8e79-f1f0c32baead"
        const val REFUND_ID = "d48c9a7b-954e-4356-992d-bc6222ddfd4f"
        const val LEDGER_ID = "f4f8ca2c-9c1b-4429-92ed-5040d6292ced"
        val authority = WalletRefundAuthority(
            VerifiedOperationsIdentity(
                userId = "user",
                tenantId = "tenant",
                workspaceId = "workspace",
                membershipId = "membership",
                permissions = setOf("payment.reconcile"),
                roles = setOf("COMPANY_OWNER")
            ),
            authorityEpoch = 7
        )
    }
}
