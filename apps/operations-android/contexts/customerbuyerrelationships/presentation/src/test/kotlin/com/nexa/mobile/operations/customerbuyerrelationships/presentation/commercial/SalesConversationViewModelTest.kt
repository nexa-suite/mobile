package com.nexa.mobile.operations.customerbuyerrelationships.presentation.commercial

import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesConversationBuyer
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesConversationGateway
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesConversationMessage
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesConversationProjection
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesConversationRead
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesMessageSendResult
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesMessagingAuthority
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesPendingMessage
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesPendingMessageRead
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
class SalesConversationViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test
    fun unknownSendKeepsTheExactPendingCommandForRetryUntilServerConfirmation() = runTest {
        val gateway = FakeGateway().apply {
            sendResults.add(SalesMessageSendResult.Unavailable)
            sendResults.add(SalesMessageSendResult.Confirmed)
        }
        val viewModel = SalesConversationViewModel(gateway)
        viewModel.activate(authority(), ACCOUNT_ID)
        runCurrent()

        viewModel.send(BUYER_ID, "Please confirm the delivery window")
        runCurrent()
        val stored = viewModel.state.value.pending
        assertEquals(SalesConversationStatus.Unavailable, viewModel.state.value.status)
        assertEquals("Please confirm the delivery window", stored?.body)
        assertEquals(1, gateway.sent.size)
        assertEquals(false, gateway.sent.single().second)

        viewModel.retryPending()
        runCurrent()

        assertEquals(2, gateway.sent.size)
        assertEquals(true, gateway.sent.last().second)
        assertEquals(stored, gateway.sent.last().first)
        assertEquals(1L, viewModel.state.value.confirmedSendRevision)
        assertNull(viewModel.state.value.pending)
        assertEquals(SalesConversationStatus.Current, viewModel.state.value.status)
    }

    @Test
    fun lateConversationResponseAfterDeactivationCannotRestorePrivateMessages() = runTest {
        val response = CompletableDeferred<SalesConversationRead>()
        val gateway = FakeGateway().apply { nextLoad = response }
        val viewModel = SalesConversationViewModel(gateway)
        viewModel.activate(authority(), ACCOUNT_ID)
        runCurrent()
        viewModel.deactivate()
        response.complete(SalesConversationRead.Current(projection()))
        runCurrent()

        assertEquals(SalesConversationStatus.Idle, viewModel.state.value.status)
        assertNull(viewModel.state.value.accountId)
        assertNull(viewModel.state.value.projection)
    }

    @Test
    fun readOnlyAuthorityCannotSendOrRetryMessages() = runTest {
        val gateway = FakeGateway()
        val viewModel = SalesConversationViewModel(gateway)
        viewModel.activate(authority(permissions = setOf("client.read")), ACCOUNT_ID)
        runCurrent()

        viewModel.send(BUYER_ID, "Not permitted")
        viewModel.retryPending()
        runCurrent()

        assertTrue(gateway.sent.isEmpty())
        assertEquals(SalesConversationStatus.Current, viewModel.state.value.status)
    }

    @Test
    fun duplicateRetryAndRefreshDuringSendCannotInvalidateTheInFlightConfirmation() = runTest {
        val pending = SalesPendingMessage(
            ACCOUNT_ID,
            BUYER_ID,
            "Please confirm the delivery window",
            "2bef4481-9dd0-4ab1-8038-9d784577c9c9"
        )
        val result = CompletableDeferred<SalesMessageSendResult>()
        val gateway = FakeGateway().apply {
            pendingValue = pending
            nextSend = result
        }
        val viewModel = SalesConversationViewModel(gateway)
        viewModel.activate(authority(), ACCOUNT_ID)
        runCurrent()

        viewModel.retryPending()
        runCurrent()
        viewModel.retryPending()
        viewModel.refresh()
        runCurrent()

        assertEquals(SalesConversationStatus.Sending, viewModel.state.value.status)
        assertEquals(1, gateway.sent.size)

        result.complete(SalesMessageSendResult.Confirmed)
        runCurrent()

        assertEquals(1L, viewModel.state.value.confirmedSendRevision)
        assertNull(viewModel.state.value.pending)
        assertEquals(SalesConversationStatus.Current, viewModel.state.value.status)
    }

    private fun authority(permissions: Set<String> = setOf("client.read", "client.manage")) =
        SalesMessagingAuthority("user", "tenant", "workspace", "membership", permissions, 7)

    private fun projection() = SalesConversationProjection(
        buyers = listOf(SalesConversationBuyer(BUYER_ID, "Buyer")),
        messages = listOf(SalesConversationMessage("BUYER", "Hello", "2026-11-16T10:00:00Z"))
    )

    private class FakeGateway : SalesConversationGateway {
        val sent = mutableListOf<Pair<SalesPendingMessage, Boolean>>()
        val sendResults = ArrayDeque<SalesMessageSendResult>()
        var pendingValue: SalesPendingMessage? = null
        var nextLoad: CompletableDeferred<SalesConversationRead>? = null
        var nextSend: CompletableDeferred<SalesMessageSendResult>? = null

        override suspend fun load(
            authority: SalesMessagingAuthority,
            accountId: String
        ): SalesConversationRead = nextLoad?.await() ?: SalesConversationRead.Current(
            SalesConversationProjection(
                buyers = listOf(SalesConversationBuyer(BUYER_ID, "Buyer")),
                messages = listOf(
                    SalesConversationMessage("BUYER", "Hello", "2026-11-16T10:00:00Z")
                )
            )
        )

        override suspend fun pending(authority: SalesMessagingAuthority): SalesPendingMessageRead =
            SalesPendingMessageRead.Current(pendingValue)

        override suspend fun send(
            authority: SalesMessagingAuthority,
            message: SalesPendingMessage,
            isRetry: Boolean
        ): SalesMessageSendResult {
            sent += message to isRetry
            val result = nextSend?.await() ?: sendResults.removeFirst()
            pendingValue = if (result == SalesMessageSendResult.Confirmed) null else message
            return result
        }
    }

    private companion object {
        const val ACCOUNT_ID = "3c6d40e7-2a59-486f-b60b-021f115e6ca8"
        const val BUYER_ID = "661516da-54d9-4d87-926a-338f375376a4"
    }
}
