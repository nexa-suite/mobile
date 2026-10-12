package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import androidx.lifecycle.ViewModelStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandIntentRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayCommandStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayLocationAcquisition
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayLocationCapture
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayLocationEventStream
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverWorkdayReadResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkday
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayLocationEvent
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayLocationSample
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkdayStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriverWorkdayViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()
    private val viewModelStores = mutableListOf<ViewModelStore>()

    @Test
    fun volatileLocationEventsReachAnActiveCollector() = runTestWithViewModelCleanup {
        val stream = DriverWorkdayLocationEventStream()
        val received = mutableListOf<DriverWorkdayLocationEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.events.collect { received.add(it) }
        }
        val event = sampleEvent()

        assertTrue(stream.publish(event))
        assertEquals(listOf(event), received)
    }

    @Test
    fun locationEventsPublishedWithoutACollectorAreNotReplayedLater() =
        runTestWithViewModelCleanup {
            val stream = DriverWorkdayLocationEventStream()
            val received = mutableListOf<DriverWorkdayLocationEvent>()
            assertTrue(stream.publish(sampleEvent()))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                stream.events.collect { received.add(it) }
            }
            runCurrent()

            assertTrue(received.isEmpty())
        }

    @Test
    fun startOnlyCapturesAfterFreshCurrentReadConfirmsActiveWorkday() =
        runTestWithViewModelCleanup {
            val events = mutableListOf<String>()
            val capture = FakeCapture(events)
            val gateway = FakeGateway(events).apply {
                reads += DriverWorkdayReadResult.Current(null)
                reads += DriverWorkdayReadResult.Current(null)
                reads += DriverWorkdayReadResult.Current(activeWorkday())
                commands += DriverWorkdayCommandResult.Accepted
            }
            val viewModel = track(DriverWorkdayViewModel(gateway, capture, FakeCommandStore()))
            viewModel.activate(AUTHORITY, hasFineLocationPermission = true)
            runCurrent()

            viewModel.startWorkday()
            runCurrent()

            assertEquals(
                listOf("get", "get", "start", "get", "capture.start"),
                events.filter {
                    it !=
                        "capture.stop"
                }
            )
            assertEquals("ACTIVE", viewModel.state.value.workday?.status?.name)
            assertTrue(viewModel.state.value.captureRequested)
        }

    @Test
    fun readOnlyAuthorityNeverStartsLocationCapture() = runTestWithViewModelCleanup {
        val events = mutableListOf<String>()
        val capture = FakeCapture(events)
        val gateway = FakeGateway(events).apply {
            reads += DriverWorkdayReadResult.Current(activeWorkday())
        }
        val viewModel = track(DriverWorkdayViewModel(gateway, capture, FakeCommandStore()))
        val readOnly = AUTHORITY.copy(permissions = setOf("dispatch.read", "logistics:read"))

        viewModel.activate(readOnly, hasFineLocationPermission = true)
        runCurrent()

        assertEquals(0, capture.starts)
        assertFalse(viewModel.state.value.captureRequested)
        assertEquals(DriverWorkdayNotice.PERMISSION_DENIED, viewModel.state.value.notice)
    }

    @Test
    fun unknownStartWithNoCurrentWorkdayDoesNotStartCapture() = runTestWithViewModelCleanup {
        val events = mutableListOf<String>()
        val capture = FakeCapture(events)
        val gateway = FakeGateway(events).apply {
            reads += DriverWorkdayReadResult.Current(null)
            reads += DriverWorkdayReadResult.Current(null)
            reads += DriverWorkdayReadResult.Current(null)
            reads += DriverWorkdayReadResult.Current(null)
            reads += DriverWorkdayReadResult.Current(activeWorkday())
            commands += DriverWorkdayCommandResult.UnknownOutcome
            commands += DriverWorkdayCommandResult.Accepted
        }
        val commandStore = FakeCommandStore()
        val viewModel = track(
            DriverWorkdayViewModel(
                gateway,
                capture,
                commandStore,
                keyFactory = { IDEMPOTENCY_KEY }
            )
        )
        viewModel.activate(AUTHORITY, hasFineLocationPermission = true)
        runCurrent()
        viewModel.startWorkday()
        runCurrent()

        assertEquals(0, capture.starts)
        assertFalse(viewModel.state.value.captureRequested)
        assertEquals(DriverWorkdayNotice.START_UNKNOWN, viewModel.state.value.notice)
        assertEquals(IDEMPOTENCY_KEY, commandStore.intent?.idempotencyKey)

        viewModel.retryPendingCommand()
        runCurrent()

        assertEquals(listOf(IDEMPOTENCY_KEY, IDEMPOTENCY_KEY), gateway.startKeys)
        assertEquals(1, capture.starts)
        assertEquals(null, commandStore.intent)
        assertTrue(viewModel.state.value.captureRequested)
    }

    @Test
    fun endStopsForegroundCaptureBeforeMutationAndDoesNotResumeFromMutationReply() =
        runTestWithViewModelCleanup {
            val events = mutableListOf<String>()
            val capture = FakeCapture(events)
            val gateway = FakeGateway(events).apply {
                reads += DriverWorkdayReadResult.Current(activeWorkday())
                commands += DriverWorkdayCommandResult.Accepted
            }
            val viewModel = track(DriverWorkdayViewModel(gateway, capture, FakeCommandStore()))
            viewModel.activate(AUTHORITY, hasFineLocationPermission = true)
            runCurrent()
            events.clear()

            viewModel.endWorkday()
            runCurrent()

            assertEquals(listOf("capture.stop", "end"), events)
            assertFalse(viewModel.state.value.captureRequested)
            assertEquals(DriverWorkdayNotice.END_PENDING_CONFIRMATION, viewModel.state.value.notice)
        }

    private class FakeGateway(private val events: MutableList<String>) : DriverWorkdayGateway {
        val reads = ArrayDeque<DriverWorkdayReadResult>()
        val commands = ArrayDeque<DriverWorkdayCommandResult>()
        val startKeys = mutableListOf<String>()
        override suspend fun current(authority: DriverDeliveryAuthority): DriverWorkdayReadResult {
            events += "get"
            return reads.removeFirstOrNull() ?: DriverWorkdayReadResult.Unavailable
        }
        override suspend fun start(
            authority: DriverDeliveryAuthority,
            idempotencyKey: String
        ): DriverWorkdayCommandResult {
            events += "start"
            startKeys += idempotencyKey
            return commands.removeFirst()
        }
        override suspend fun end(
            authority: DriverDeliveryAuthority,
            workdayId: String,
            version: Long,
            idempotencyKey: String
        ): DriverWorkdayCommandResult {
            events += "end"
            return commands.removeFirst()
        }
        override suspend fun setLocationAvailability(
            authority: DriverDeliveryAuthority,
            workdayId: String,
            version: Long,
            locationAvailable: Boolean,
            idempotencyKey: String
        ): DriverWorkdayCommandResult {
            events += "availability"
            return commands.removeFirst()
        }
        override suspend fun reportLocation(
            authority: DriverDeliveryAuthority,
            workdayId: String,
            location: DriverWorkdayLocationSample
        ): DriverWorkdayCommandResult {
            events += "location"
            return DriverWorkdayCommandResult.Accepted
        }
    }

    private class FakeCommandStore : DriverWorkdayCommandStore {
        var intent: DriverWorkdayCommandIntent? = null
        override suspend fun load(
            scope: DriverWorkdayCommandScope
        ): DriverWorkdayCommandIntentRead =
            DriverWorkdayCommandIntentRead.Available(intent?.takeIf { it.scope == scope })
        override suspend fun save(intent: DriverWorkdayCommandIntent): Boolean {
            if (this.intent != null && this.intent != intent) return false
            this.intent = intent
            return true
        }
        override suspend fun clear(
            scope: DriverWorkdayCommandScope,
            idempotencyKey: String
        ): Boolean {
            if (intent == null) return true
            if (intent?.scope != scope || intent?.idempotencyKey != idempotencyKey) return false
            intent = null
            return true
        }
    }

    private class FakeCapture(private val log: MutableList<String>) : DriverWorkdayLocationCapture {
        private val mutableEvents = MutableSharedFlow<DriverWorkdayLocationEvent>()
        override val events: Flow<DriverWorkdayLocationEvent> = mutableEvents
        var starts = 0
        override suspend fun acquireFreshLocation(
            timeoutMillis: Long
        ): DriverWorkdayLocationAcquisition = DriverWorkdayLocationAcquisition.SAMPLE_AVAILABLE
        override fun start(workdayId: String): Boolean {
            starts += 1
            log += "capture.start"
            return true
        }
        override fun stop() {
            log += "capture.stop"
        }
    }

    private fun activeWorkday() = DriverWorkday(
        WORKDAY_ID,
        4,
        DriverWorkdayStatus.ACTIVE,
        "2026-10-01T17:00:00Z",
        null,
        true
    )

    private fun sampleEvent() = DriverWorkdayLocationEvent.Sample(
        captureId = WORKDAY_ID,
        value = DriverWorkdayLocationSample(
            SAMPLE_ID,
            -12.05,
            -77.04,
            9.5,
            "2026-10-01T17:30:00Z"
        )
    )

    private fun track(viewModel: DriverWorkdayViewModel): DriverWorkdayViewModel {
        val store = ViewModelStore()
        store.put("driver-workday-test", viewModel)
        viewModelStores += store
        return viewModel
    }

    private fun runTestWithViewModelCleanup(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block(this)
        } finally {
            viewModelStores.forEach(ViewModelStore::clear)
            viewModelStores.clear()
        }
    }

    private companion object {
        val AUTHORITY = DriverDeliveryAuthority(
            USER_ID,
            TENANT_ID,
            WORKSPACE_ID,
            MEMBER_ID,
            setOf("dispatch.read", "dispatch.start_route"),
            authorityEpoch = 3
        )
        const val USER_ID = "11111111-1111-4111-8111-111111111111"
        const val TENANT_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val MEMBER_ID = "44444444-4444-4444-8444-444444444444"
        const val WORKDAY_ID = "55555555-5555-4555-8555-555555555555"
        const val IDEMPOTENCY_KEY = "66666666-6666-4666-8666-666666666666"
        const val SAMPLE_ID = "77777777-7777-4777-8777-777777777777"
    }
}
