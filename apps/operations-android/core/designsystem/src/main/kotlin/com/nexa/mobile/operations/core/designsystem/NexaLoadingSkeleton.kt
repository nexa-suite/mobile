package com.nexa.mobile.operations.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * A static loading placeholder that is exposed as one localized progress item to accessibility
 * services. Use a contextual [semanticsLabel], and keep decorative placeholder details inside
 * this single component so they are not exposed as separate accessibility nodes.
 *
 * The static treatment also respects reduced-motion preferences without requiring animation
 * settings or additional dependencies.
 */
@Composable
fun NexaLoadingSkeleton(
    semanticsLabel: String,
    modifier: Modifier = Modifier,
    size: DpSize = DpSize(width = 120.dp, height = 16.dp),
    shape: Shape = NexaShapes.control
) {
    val loadingDescription = stringResource(R.string.nexa_loading)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(NexaColors.SurfaceInset)
            .clearAndSetSemantics {
                contentDescription = semanticsLabel
                stateDescription = loadingDescription
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                liveRegion = LiveRegionMode.Polite
            }
    )
}
