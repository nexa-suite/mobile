package com.nexa.mobile.operations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackBanner
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackTone
import com.nexa.mobile.operations.feature.access.AccessNotice
import com.nexa.mobile.operations.feature.access.R as AccessR

/** Screen-level notice composition; route and authority decisions remain in RootNavigation. */
@Composable
internal fun WarehouseContentNotice(
    notice: AccessNotice?,
    content: @Composable ColumnScope.() -> Unit
) {
    val resource = when (notice) {
        AccessNotice.ContextSelectionRejected -> AccessR.string.access_notice_context_rejected
        AccessNotice.ContextSelectionUnavailable -> AccessR.string.access_notice_context_unavailable
        else -> null
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (resource != null) {
            NexaFeedbackBanner(
                message = stringResource(resource),
                tone = NexaFeedbackTone.Warning,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        content()
    }
}
