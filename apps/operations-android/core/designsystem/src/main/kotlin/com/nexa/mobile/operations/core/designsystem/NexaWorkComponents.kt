package com.nexa.mobile.operations.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Groups current work facts without deriving a business status from their presentation. */
@Composable
fun NexaWorkSummary(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NexaShapes.card,
        color = NexaColors.InfoSurface,
        border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border)
    ) {
        Column(
            modifier = Modifier.padding(NexaSpacing.summaryInset),
            verticalArrangement = Arrangement.spacedBy(NexaSpacing.base)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = NexaColors.PrimaryDeep)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = NexaColors.TextSecondary
            )
            content()
        }
    }
}

/** Stable section hierarchy for operational facts, evidence and actions. */
@Composable
fun NexaWorkSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NexaShapes.card,
        color = NexaColors.Surface,
        border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border)
    ) {
        Column(
            modifier = Modifier.padding(NexaSpacing.standard),
            verticalArrangement = Arrangement.spacedBy(NexaSpacing.compact)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = NexaColors.TextPrimary
            )
            content()
        }
    }
}
