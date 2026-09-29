package com.nexa.mobile.operations

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Match system icon contrast to the currently rendered native screen. */
@Composable
internal fun NexaSystemBars(authCanopyVisible: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, authCanopyVisible) {
        val activity = view.context.findActivity()
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = !authCanopyVisible
        controller?.isAppearanceLightNavigationBars = true
        onDispose {
            if (previousStatus != null) controller?.isAppearanceLightStatusBars = previousStatus
            if (previousNavigation != null) {
                controller?.isAppearanceLightNavigationBars = previousNavigation
            }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
