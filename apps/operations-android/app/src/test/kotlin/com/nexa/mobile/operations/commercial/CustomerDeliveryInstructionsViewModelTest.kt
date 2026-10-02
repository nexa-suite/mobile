package com.nexa.mobile.operations.commercial

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CustomerDeliveryInstructionsViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val authority =
        CommercialAuthority("u", "t", "w", "m", setOf("client.manage", "sales.order.read"), 1)
    private val snapshot = CustomerInstructionSnapshot("order", 3, true, emptyList())
    private class Store(var available: Boolean = true) : CustomerInstructionStore {
        var command: CustomerInstructionCommand? = null
        override suspend fun load(authority: CommercialAuthority): CustomerInstructionStored =
            CustomerInstructionStored.Loaded(command)
        override suspend fun save(
            authority: CommercialAuthority,
            command: CustomerInstructionCommand
        ): Boolean {
            if (available) this.command = command
            return available
        }
        override suspend fun clear(authority: CommercialAuthority, key: String): Boolean {
            if (command?.key !=
                key
            ) {
                return false
            }
            command = null
            return true
        }
    }
    private class Gateway(val snapshot: CustomerInstructionSnapshot) : CustomerInstructionGateway {
        val commands = mutableListOf<CustomerInstructionCommand>()
        var result: CustomerInstructionResult = CustomerInstructionResult.Failed("UNKNOWN", true)
        override suspend fun read(authority: CommercialAuthority, orderId: String) =
            CustomerInstructionResult.Current(snapshot)
        override suspend fun publish(
            authority: CommercialAuthority,
            command: CustomerInstructionCommand
        ): CustomerInstructionResult {
            commands += command
            return result
        }
    }
    private suspend fun TestScope.prepare(model: CustomerDeliveryInstructionsViewModel) {
        model.activate(authority)
        runCurrent()
        model.orderChanged("order")
        model.refresh()
        runCurrent()
        model.contentChanged("Customer unloading instruction")
        model.sourceChanged("Customer visit, recorded by Sales")
    }

    @Test fun storageFailurePreventsDispatch() = runTest {
        val gateway = Gateway(snapshot)
        val store = Store(false)
        val model = CustomerDeliveryInstructionsViewModel(gateway, store)
        prepare(model)
        model.publish()
        runCurrent()
        assertTrue(gateway.commands.isEmpty())
        assertFalse(model.state.value.storageAvailable)
    }

    @Test fun unknownOutcomeRetainsExactCommandAndBlocksNewDecisionAcrossRestart() = runTest {
        val gateway = Gateway(snapshot)
        val store = Store()
        val first = CustomerDeliveryInstructionsViewModel(gateway, store)
        prepare(first)
        first.publish()
        runCurrent()
        val original = gateway.commands.single()
        first.contentChanged("Changed")
        first.publish()
        runCurrent()
        assertEquals(1, gateway.commands.size)
        val restarted = CustomerDeliveryInstructionsViewModel(gateway, store)
        restarted.activate(authority)
        runCurrent()
        assertEquals(original, restarted.state.value.recoverable)
        gateway.result = CustomerInstructionResult.Current(snapshot.copy(version = 4))
        restarted.retry()
        runCurrent()
        assertEquals(listOf(original, original), gateway.commands)
        assertNull(store.command)
        assertNull(restarted.state.value.recoverable)
    }

    @Test fun frozenReadWindowPreventsClientPublish() = runTest {
        val gateway = Gateway(snapshot.copy(editable = false))
        val model = CustomerDeliveryInstructionsViewModel(gateway, Store())
        prepare(model)
        model.publish()
        runCurrent()
        assertTrue(gateway.commands.isEmpty())
    }

    @Test fun lateResultCannotRestoreFactsAfterContextLoss() = runTest {
        val deferred = CompletableDeferred<CustomerInstructionResult>()
        val gateway = object : CustomerInstructionGateway {
            override suspend fun read(authority: CommercialAuthority, orderId: String) =
                deferred.await()
            override suspend fun publish(
                authority: CommercialAuthority,
                command: CustomerInstructionCommand
            ) = error("Unexpected mutation")
        }
        val model = CustomerDeliveryInstructionsViewModel(gateway, Store())
        model.activate(authority)
        runCurrent()
        model.orderChanged("order")
        model.refresh()
        runCurrent()
        model.deactivate()
        deferred.complete(CustomerInstructionResult.Current(snapshot))
        runCurrent()
        assertNull(model.state.value.snapshot)
    }
}
