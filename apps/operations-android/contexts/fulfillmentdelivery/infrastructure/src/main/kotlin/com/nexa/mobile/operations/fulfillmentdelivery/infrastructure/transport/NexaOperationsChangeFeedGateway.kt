package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.fulfillmentdelivery.application.changefeed.OperationsChangeFeedGateway
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource

/** Authenticated SSE transport. Frames expose only refresh signals, never server state. */
@Singleton
class NexaOperationsChangeFeedGateway @Inject constructor(
    private val endpoint: ApiEndpoint,
    client: OkHttpClient,
    private val tokens: AccessTokenSource,
    applicationScope: CoroutineScope
) : OperationsChangeFeedGateway {
    private val streamClient = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val cursorLock = Any()
    private var cursorEpoch: Long? = null
    private var lastEventId: Long? = null

    private val sharedEventNames = changeEventNames()
        .conflate()
        .shareIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0),
            replay = 0
        )

    override fun relevantChangeSignals(): Flow<Unit> = sharedEventNames
        .filter { it == RESYNC_REQUIRED_EVENT || it in RELEVANT_OPERATIONS_EVENT_TYPES }
        .map { }

    override fun receivingLookupChangeSignals(): Flow<Unit> = sharedEventNames
        .filter { it == RESYNC_REQUIRED_EVENT || it in RECEIVING_LOOKUP_EVENT_TYPES }
        .map { }

    override fun stockConditionChangeSignals(): Flow<Unit> = sharedEventNames
        .filter { it == RESYNC_REQUIRED_EVENT || it in STOCK_CONDITION_EVENT_TYPES }
        .map { }

    private fun changeEventNames(): Flow<String> = callbackFlow {
        var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS

        while (currentCoroutineContext().isActive) {
            val lease = tokens.currentAccess()
            if (lease == null) {
                delay(INITIAL_RETRY_DELAY_MILLIS)
                continue
            }
            val initial = connect(lease, cursorFor(lease.epoch), this)

            if (!tokens.isEpochCurrent(lease.epoch)) break
            if (initial.statusCode == HTTP_UNAUTHORIZED) {
                val replacement = tokens.recoverAfterUnauthorized(lease) ?: break
                if (replacement.epoch != lease.epoch ||
                    !tokens.isEpochCurrent(replacement.epoch)
                ) {
                    break
                }

                val replay = connect(replacement, cursorFor(replacement.epoch), this)
                if (!tokens.isEpochCurrent(replacement.epoch)) break
                if (replay.statusCode == HTTP_UNAUTHORIZED) {
                    tokens.rejectCurrentAccess(replacement)
                    break
                }
                if (replay.statusCode.isTerminalClientFailure()) break
                retryDelayMillis = reconnectDelay(replay, retryDelayMillis)
                continue
            }

            if (initial.statusCode.isTerminalClientFailure()) break
            retryDelayMillis = reconnectDelay(initial, retryDelayMillis)
        }
        close()
    }

    private suspend fun connect(
        lease: AccessTokenLease,
        cursor: Long?,
        eventNames: ProducerScope<String>
    ): ConnectionOutcome {
        var statusCode: Int? = null
        var failed = false
        val startedAt = System.nanoTime()
        try {
            openStream(lease, cursor).collect { frame ->
                when (frame) {
                    is StreamFrame.Status -> statusCode = frame.code
                    is StreamFrame.Event -> handleEvent(lease, frame, eventNames)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            failed = true
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        return ConnectionOutcome(statusCode, failed, elapsedMillis)
    }

    private suspend fun handleEvent(
        lease: AccessTokenLease,
        event: StreamFrame.Event,
        eventNames: ProducerScope<String>
    ) {
        updateCursor(lease.epoch, event.id)
        if (event.name == RESYNC_REQUIRED_EVENT) {
            resetCursor(lease.epoch)
            if (tokens.isEpochCurrent(lease.epoch)) eventNames.trySend(RESYNC_REQUIRED_EVENT)
        } else if (event.name.isRelevantForAnyOperationsConsumer() &&
            tokens.isEpochCurrent(lease.epoch)
        ) {
            eventNames.trySend(event.name)
        }
    }

    private suspend fun reconnectDelay(outcome: ConnectionOutcome, currentDelayMillis: Long): Long {
        val longLivedSuccessfulStream = outcome.statusCode?.let { it in 200..299 } == true &&
            !outcome.failed && outcome.elapsedMillis >= MIN_LONG_LIVED_STREAM_MILLIS
        if (longLivedSuccessfulStream) return INITIAL_RETRY_DELAY_MILLIS

        delay(currentDelayMillis)
        return nextRetryDelay(currentDelayMillis)
    }

    private fun openStream(lease: AccessTokenLease, cursor: Long?): Flow<StreamFrame> =
        callbackFlow {
            val url = endpoint.url.resolve(STREAM_PATH)
            if (url == null || !endpoint.isTrusted(url) ||
                !url.encodedPath.startsWith("/api/v1/")
            ) {
                close(IOException("Change-feed URL is not trusted"))
                return@callbackFlow
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${lease.value}")
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .apply { cursor?.let { header("Last-Event-ID", it.toString()) } }
                .get()
                .build()
            val call = streamClient.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    close(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { current ->
                            trySend(StreamFrame.Status(current.code))
                            if (!current.isSuccessful) return@use
                            val source = current.body?.source()
                                ?: throw IOException("Change-feed response has no body")
                            parseEvents(source) { event -> trySend(event) }
                        }
                        close()
                    } catch (failure: IOException) {
                        close(failure)
                    }
                }
            })
            awaitClose { call.cancel() }
        }

    private fun parseEvents(source: BufferedSource, onFrame: (StreamFrame.Event) -> Unit) {
        var eventName = "message"
        var eventId: Long? = null
        var hasData = false
        var isFirstLine = true

        while (true) {
            val line = try {
                source.readUtf8LineStrict(SSE_MAX_LINE_BYTES)
            } catch (_: EOFException) {
                break
            }
            val normalized = if (isFirstLine) line.removePrefix("\uFEFF") else line
            isFirstLine = false

            if (normalized.isEmpty()) {
                if (hasData) onFrame(StreamFrame.Event(eventName, eventId))
                eventName = "message"
                hasData = false
                continue
            }
            if (normalized.startsWith(':')) continue

            val colon = normalized.indexOf(':')
            val field = if (colon < 0) normalized else normalized.substring(0, colon)
            val rawValue = if (colon < 0) "" else normalized.substring(colon + 1)
            val value = rawValue.removePrefix(" ")
            when (field) {
                "event" -> eventName = value.ifEmpty { "message" }

                "id" -> {
                    if ('\u0000' !in value) eventId = value.toLongOrNull()?.takeIf { it >= 0 }
                }

                "data" -> hasData = true
            }
        }
    }

    private fun cursorFor(epoch: Long): Long? = synchronized(cursorLock) {
        if (cursorEpoch != epoch) {
            cursorEpoch = epoch
            lastEventId = null
        }
        lastEventId
    }

    private fun updateCursor(epoch: Long, value: Long?) {
        if (value == null) return
        synchronized(cursorLock) {
            if (cursorEpoch != epoch) {
                cursorEpoch = epoch
                lastEventId = null
            }
            if (lastEventId == null || value > lastEventId!!) lastEventId = value
        }
    }

    private fun resetCursor(epoch: Long) {
        synchronized(cursorLock) {
            cursorEpoch = epoch
            lastEventId = null
        }
    }

    private fun nextRetryDelay(current: Long): Long =
        (current * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)

    private fun String.isRelevantForOperations(): Boolean = this in RELEVANT_OPERATIONS_EVENT_TYPES

    private fun String.isRelevantForReceivingLookups(): Boolean =
        this in RECEIVING_LOOKUP_EVENT_TYPES

    private fun String.isRelevantForStockCondition(): Boolean = this in STOCK_CONDITION_EVENT_TYPES

    private fun String.isRelevantForAnyOperationsConsumer(): Boolean =
        isRelevantForOperations() || isRelevantForReceivingLookups() ||
            isRelevantForStockCondition()

    private fun Int?.isTerminalClientFailure(): Boolean = this != null && this in 400..499 &&
        this != HTTP_UNAUTHORIZED && this != HTTP_TOO_MANY_REQUESTS

    private data class ConnectionOutcome(
        val statusCode: Int?,
        val failed: Boolean,
        val elapsedMillis: Long
    )

    private sealed interface StreamFrame {
        data class Status(val code: Int) : StreamFrame
        data class Event(val name: String, val id: Long?) : StreamFrame
    }

    private companion object {
        const val STREAM_PATH = "/api/v1/change-feed/stream"
        const val RESYNC_REQUIRED_EVENT = "resync-required"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val SSE_MAX_LINE_BYTES = 16_384L
        const val INITIAL_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_RETRY_DELAY_MILLIS = 30_000L
        const val MIN_LONG_LIVED_STREAM_MILLIS = 45_000L
        private val RELEVANT_OPERATIONS_EVENT_TYPES = setOf(
            "logistics.dispatch.created",
            "logistics.dispatch.preparation-started",
            "logistics.dispatch.assigned",
            "logistics.dispatch.scheduled",
            "logistics.dispatch.ready",
            "logistics.dispatch.route-started",
            "logistics.dispatch.temperature-recorded",
            "logistics.dispatch.incident-recorded",
            "logistics.dispatch.reprogrammed",
            "logistics.dispatch.cancelled",
            "logistics.dispatch.delivered",
            "logistics.pod.completed",
            "logistics.delivery.attempt-failed",
            "logistics.delivery.partially-completed",
            "logistics.delivery.continuation-created",
            "warehouse.reservation.created",
            "warehouse.reservation.shortage",
            "warehouse.reservation.released",
            "warehouse.reservation.expired",
            "warehouse.reservation.consumed",
            "warehouse.operational-profile.updated",
            "sales.sales-order.confirmed"
        )
        private val RECEIVING_LOOKUP_EVENT_TYPES = setOf(
            "warehouse.warehouse.created",
            "warehouse.warehouse.updated",
            "warehouse.zone.created",
            "warehouse.zone.updated"
        )
        private val STOCK_CONDITION_EVENT_TYPES = setOf(
            "warehouse.lot.received",
            "warehouse.lot.temperature-hold",
            "warehouse.lot.adjusted",
            "warehouse.lot.waste-recorded",
            "warehouse.lot.blocked",
            "warehouse.lot.quarantined",
            "warehouse.lot.restored",
            "warehouse.lot.disposition-recorded",
            "warehouse.safety-stock.configured",
            "warehouse.safety-stock.updated",
            "warehouse.inventory.transfer.requested",
            "warehouse.inventory.transfer.dispatched",
            "warehouse.inventory.transfer.received",
            "warehouse.inventory.transfer.completed"
        )
    }
}
