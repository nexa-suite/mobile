package com.nexa.mobile.operations.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

enum class NexaFeedbackTone { Information, Success, Warning, Error }

@Composable
fun NexaTopAppBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    Surface(color = NexaColors.Surface, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.heightIn(min = NexaSizes.topBarMinHeight).padding(
                horizontal = NexaSpacing.screenHorizontal,
                vertical = NexaSpacing.topBarVertical
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexaSpacing.inline)
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(NexaSizes.minimumTouchTarget)
                ) {
                    Text(
                        stringResource(R.string.nexa_back_arrow),
                        color = NexaColors.BrandStrong,
                        style = NexaTypography.backGlyph
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NexaSpacing.textTight)
            ) {
                Text(
                    stringResource(R.string.nexa_brand_name),
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    color = NexaColors.BrandStrong
                )
                Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun NexaActiveContextBar(
    companyName: String,
    workspaceName: String,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val description = stringResource(
        if (isCurrent) {
            R.string.nexa_current_context_description
        } else {
            R.string.nexa_context_change_description
        },
        companyName,
        workspaceName
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NexaSizes.controlMinHeight)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            },
        enabled = enabled,
        shape = NexaShapes.row,
        color = NexaColors.Surface,
        border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = NexaSpacing.screenHorizontal,
                vertical = NexaSpacing.rowVertical
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NexaSpacing.textTight)
            ) {
                Text(
                    companyName,
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge
                )
                Text(
                    workspaceName,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = NexaColors.TextSecondary
                )
            }
            Spacer(Modifier.width(NexaSpacing.inline))
            Text(
                stringResource(if (isCurrent) R.string.nexa_change_context else R.string.nexa_open),
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = NexaColors.BrandStrong
            )
        }
    }
}

@Composable
fun NexaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    errorText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = NexaSizes.controlMinHeight).semantics {
            if (errorText != null) error(errorText)
        },
        label = { Text(label) },
        supportingText = {
            when {
                errorText != null -> Text(errorText)
                supportingText != null -> Text(supportingText)
            }
        },
        isError = errorText != null,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions
    )
}

@Composable
fun NexaPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onVisibilityChange: () -> Unit,
    label: String,
    showLabel: String,
    hideLabel: String,
    modifier: Modifier = Modifier,
    errorText: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = NexaSizes.controlMinHeight).semantics {
            if (errorText != null) error(errorText)
        },
        label = { Text(label) },
        supportingText = errorText?.let { { Text(it) } },
        isError = errorText != null,
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        trailingIcon = {
            IconButton(
                onClick = onVisibilityChange,
                modifier = Modifier.size(NexaSizes.minimumTouchTarget).semantics {
                    contentDescription = if (visible) hideLabel else showLabel
                }
            ) {
                Canvas(Modifier.size(NexaSizes.icon)) {
                    val eye = Path().apply {
                        moveTo(2.dp.toPx(), size.height / 2)
                        cubicTo(
                            size.width * 0.28f,
                            size.height * 0.12f,
                            size.width * 0.72f,
                            size.height * 0.12f,
                            size.width - 2.dp.toPx(),
                            size.height / 2
                        )
                        cubicTo(
                            size.width * 0.72f,
                            size.height * 0.88f,
                            size.width * 0.28f,
                            size.height * 0.88f,
                            2.dp.toPx(),
                            size.height / 2
                        )
                        close()
                    }
                    drawPath(eye, NexaColors.TextSecondary, style = Stroke(width = 2.dp.toPx()))
                    drawCircle(NexaColors.TextSecondary, radius = 2.5.dp.toPx())
                    if (!visible) {
                        drawLine(
                            NexaColors.TextSecondary,
                            Offset(4.dp.toPx(), 4.dp.toPx()),
                            Offset(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }
            }
        }
    )
}

@Composable
fun NexaPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val loadingStateDescription = if (loading) {
        stringResource(R.string.nexa_loading)
    } else {
        null
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = NexaSizes.primaryButtonMinHeight)
            .semantics {
                if (loadingStateDescription != null) {
                    stateDescription = loadingStateDescription
                }
            },
        enabled = enabled && !loading,
        shape = NexaShapes.button
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(NexaSizes.buttonProgress),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(NexaSpacing.buttonIndicatorGap))
        }
        Text(label)
    }
}

@Composable
fun NexaContextChoiceRow(
    companyName: String,
    workspaceName: String,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    pending: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val desc = if (current) {
        stringResource(R.string.nexa_current_context_description, companyName, workspaceName)
    } else {
        stringResource(R.string.nexa_context_choice_description, companyName, workspaceName)
    }
    val pendingStateDescription = if (pending) {
        stringResource(R.string.nexa_context_pending)
    } else {
        null
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NexaSizes.contextChoiceMinHeight)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = desc
                if (pendingStateDescription != null) {
                    stateDescription = pendingStateDescription
                }
            },
        enabled = enabled && !pending,
        shape = NexaShapes.row,
        color = NexaColors.Surface,
        border = BorderStroke(
            NexaSizes.borderWidth,
            if (current) NexaColors.Brand else NexaColors.Border
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = NexaSpacing.screenHorizontal,
                vertical = NexaSpacing.rowVertical
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexaSpacing.inline)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NexaSpacing.textCompact)
            ) {
                Text(
                    companyName,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                )
                Text(
                    workspaceName,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = NexaColors.TextSecondary
                )
                if (current) {
                    Text(
                        stringResource(R.string.nexa_actual),
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        color = NexaColors.BrandStrong
                    )
                }
            }
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(NexaSizes.progress),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
fun NexaTaskRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val rowDescription = stringResource(
        R.string.nexa_task_row_action_description,
        title,
        description
    )
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(
            min = NexaSizes.taskRowMinHeight
        ).semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = rowDescription
        },
        shape = NexaShapes.row,
        color = NexaColors.Surface,
        border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = NexaSpacing.screenHorizontal,
                vertical = NexaSpacing.surfaceInset
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NexaSpacing.textCompact)
            ) {
                Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = NexaColors.TextSecondary
                )
            }
            Spacer(Modifier.width(NexaSpacing.inline))
            Text(
                stringResource(R.string.nexa_open),
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = NexaColors.BrandStrong
            )
        }
    }
}

@Composable
fun NexaSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
    errorText: String? = null,
    onImeSearch: () -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = NexaSizes.controlMinHeight).semantics {
            if (errorText != null) error(errorText)
        },
        label = { Text(label) },
        placeholder = { Text(hint) },
        supportingText = errorText?.let { { Text(it) } },
        isError = errorText != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onImeSearch() })
    )
}

@Composable
fun NexaProductCandidateRow(
    name: String,
    variant: String?,
    presentation: String,
    sku: String,
    modifier: Modifier = Modifier,
    pending: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val candidateDetails = listOfNotNull(
        name,
        variant?.takeIf { it.isNotBlank() },
        presentation,
        stringResource(R.string.nexa_candidate_sku, sku)
    ).joinToString()
    val rowDescription = stringResource(
        R.string.nexa_candidate_row_action_description,
        candidateDetails
    )
    val pendingStateDescription = if (pending) {
        stringResource(R.string.nexa_loading)
    } else {
        null
    }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(
            min = NexaSizes.candidateRowMinHeight
        ).semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = rowDescription
            if (pendingStateDescription != null) {
                stateDescription = pendingStateDescription
            }
        },
        enabled = enabled && !pending,
        shape = NexaShapes.row,
        color = NexaColors.Surface,
        border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = NexaSpacing.screenHorizontal,
                vertical = NexaSpacing.candidateRowVertical
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexaSpacing.inline)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NexaSpacing.textCompact)
            ) {
                Text(name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                if (!variant.isNullOrBlank()) {
                    Text(
                        variant,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = NexaColors.TextSecondary
                    )
                }
                Text(
                    presentation,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = NexaColors.TextSecondary
                )
                Text(
                    stringResource(R.string.nexa_candidate_sku, sku),
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    color = NexaColors.TextMuted
                )
            }
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(NexaSizes.progress),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
fun NexaFeedbackBanner(message: String, tone: NexaFeedbackTone, modifier: Modifier = Modifier) {
    val (label, background, foreground) = when (tone) {
        NexaFeedbackTone.Information -> Triple(
            R.string.nexa_status_information,
            NexaColors.InfoSurface,
            NexaColors.Info
        )

        NexaFeedbackTone.Success -> Triple(
            R.string.nexa_status_success,
            NexaColors.SuccessSurface,
            NexaColors.Success
        )

        NexaFeedbackTone.Warning -> Triple(
            R.string.nexa_status_warning,
            NexaColors.WarningSurface,
            NexaColors.Warning
        )

        NexaFeedbackTone.Error -> Triple(
            R.string.nexa_status_error,
            NexaColors.DangerSurface,
            NexaColors.Danger
        )
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = NexaShapes.surface,
        color = background,
        border = BorderStroke(NexaSizes.borderWidth, foreground.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier.padding(NexaSpacing.surfaceInset),
            verticalArrangement = Arrangement.spacedBy(NexaSpacing.textCompact)
        ) {
            Text(
                stringResource(label),
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = foreground
            )
            Text(
                message,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = NexaColors.TextPrimary
            )
        }
    }
}

@Composable
fun NexaStatePanel(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = NexaShapes.surface,
        color = NexaColors.Surface,
        border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border)
    ) {
        Column(
            modifier = Modifier.padding(NexaSpacing.surfaceInset),
            verticalArrangement = Arrangement.spacedBy(NexaSpacing.inline)
        ) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = NexaColors.TextSecondary
            )
            if (actionLabel != null && onAction != null) {
                NexaPrimaryButton(label = actionLabel, onClick = onAction)
            }
        }
    }
}

@Composable
fun NexaConfirmedSkuSummary(
    productName: String,
    variant: String?,
    presentation: String,
    sku: String,
    brand: String?,
    unit: String?,
    packaging: String?,
    coldChain: String?,
    activeContext: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NexaShapes.surface,
        color = NexaColors.Surface,
        border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border)
    ) {
        Column(
            modifier = Modifier.padding(NexaSpacing.summaryInset),
            verticalArrangement = Arrangement.spacedBy(NexaSpacing.inline)
        ) {
            Text(
                productName,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge
            )
            if (!variant.isNullOrBlank()) {
                AttributeRow(
                    stringResource(R.string.nexa_variant),
                    variant
                )
            }
            AttributeRow(stringResource(R.string.nexa_presentation), presentation)
            AttributeRow(stringResource(R.string.nexa_candidate_sku_label), sku)
            if (!brand.isNullOrBlank()) AttributeRow(stringResource(R.string.nexa_brand), brand)
            if (!unit.isNullOrBlank()) AttributeRow(stringResource(R.string.nexa_unit), unit)
            if (!packaging.isNullOrBlank()) {
                AttributeRow(
                    stringResource(R.string.nexa_packaging),
                    packaging
                )
            }
            if (!coldChain.isNullOrBlank()) {
                AttributeRow(
                    stringResource(R.string.nexa_cold_chain),
                    coldChain
                )
            }
            AttributeRow(stringResource(R.string.nexa_context), activeContext)
        }
    }
}

@Composable
private fun AttributeRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(NexaSpacing.textTight)) {
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            color = NexaColors.TextMuted
        )
        Text(value, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
    }
}
