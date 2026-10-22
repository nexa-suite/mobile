package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@Composable
fun DispatchReadinessAutoRefresh(
    state: DispatchReadinessUiState,
    onRefresh: () -> Unit,
    enabled: Boolean = true
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true

                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> isResumed = false

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val latestState = rememberUpdatedState(state)
    val refreshAction = rememberUpdatedState(onRefresh)
    val authorityEpoch = state.authorityEpoch
    val changeFeedSignals = state.changeFeedSignals
    LaunchedEffect(lifecycleOwner, isResumed, authorityEpoch, enabled, changeFeedSignals) {
        if (!isResumed || !enabled || authorityEpoch <= 0) return@LaunchedEffect
        launch {
            changeFeedSignals.debounce(CHANGE_FEED_COALESCE_MILLIS).collect {
                while (true) {
                    val current = latestState.value
                    if (current.authorityEpoch != authorityEpoch ||
                        current.selectedFulfillmentId != null
                    ) {
                        return@collect
                    }
                    if (current.status == DispatchReadinessStatus.Loading) {
                        delay(CHANGE_FEED_LOADING_RECHECK_MILLIS)
                        continue
                    }
                    if (current.status !in REFRESHABLE_STATUS) return@collect
                    refreshAction.value()
                    return@collect
                }
            }
        }
        while (true) {
            delay(REFRESH_INTERVAL_MILLIS)
            val current = latestState.value
            if (current.authorityEpoch != authorityEpoch) return@LaunchedEffect
            if (current.status in REFRESHABLE_STATUS) refreshAction.value()
        }
    }
}

private const val REFRESH_INTERVAL_MILLIS = 15_000L
private const val CHANGE_FEED_COALESCE_MILLIS = 250L
private const val CHANGE_FEED_LOADING_RECHECK_MILLIS = 100L
private val REFRESHABLE_STATUS = setOf(
    DispatchReadinessStatus.Current,
    DispatchReadinessStatus.Empty,
    DispatchReadinessStatus.NetworkUnavailable,
    DispatchReadinessStatus.ServiceUnavailable
)
