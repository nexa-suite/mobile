package com.nexa.mobile.operations.core.designsystem

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class NexaFeedbackTone { Information, Success, Warning, Error }

enum class NexaColdChainTone { Neutral, Refrigerated, Frozen }

private data class NexaFeedbackPalette(
    val label: Int,
    val background: Color,
    val border: Color,
    val foreground: Color
)

@Composable
fun NexaTopAppBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    Surface(color = NexaColors.Surface, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.heightIn(min = NexaSizes.topBarMinHeight).padding(
                    horizontal = NexaSpacing.screenHorizontal,
                    vertical = NexaSpacing.topBarVertical
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NexaSpacing.compact)
            ) {
                if (onBack != null) {
                    val backDescription = stringResource(R.string.nexa_back)
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(NexaSizes.minimumTouchTarget).semantics {
                            contentDescription = backDescription
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = null,
                            tint = NexaColors.PrimaryStrong
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(NexaSpacing.textTight)
                ) {
                    Text(
                        stringResource(R.string.nexa_brand_name),
                        style = MaterialTheme.typography.labelSmall,
                        color = NexaColors.PrimaryStrong
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = NexaColors.TextPrimary
                    )
                }
            }
            HorizontalDivider(thickness = NexaSizes.borderWidth, color = NexaColors.Border)
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
            .heightIn(min = NexaSizes.activeContextMinHeight)
            .semantics(mergeDescendants = true) {
                if (enabled) role = Role.Button
                contentDescription = description
            },
        enabled = enabled,
        shape = NexaShapes.row,
        color = NexaColors.Surface,
        border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border)
    ) {
        BoxWithConstraints {
            val actionLabel = stringResource(
                if (isCurrent) R.string.nexa_change_context else R.string.nexa_open
            )
            val actionColor = if (enabled) NexaColors.PrimaryStrong else NexaColors.TextSecondary
            val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f
            val contentPadding = Modifier.padding(
                horizontal = NexaSpacing.screenHorizontal,
                vertical = NexaSpacing.rowVertical
            )
            if (stacked) {
                Column(
                    modifier = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(NexaSpacing.base)
                ) {
                    ContextNameLabels(companyName, workspaceName)
                    Text(
                        actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = actionColor
                    )
                }
            } else {
                Row(
                    modifier = contentPadding,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ContextNameLabels(
                        companyName,
                        workspaceName,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(NexaSpacing.base))
                    Text(
                        actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = actionColor
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextNameLabels(
    companyName: String,
    workspaceName: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NexaSpacing.textTight)
    ) {
        Text(
            companyName,
            style = MaterialTheme.typography.titleSmall,
            color = NexaColors.TextPrimary
        )
        Text(
            workspaceName,
            style = MaterialTheme.typography.bodyMedium,
            color = NexaColors.TextSecondary
        )
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
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true
) {
    NexaUnderlinedField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIconRes = R.drawable.ic_identity,
        supportingText = supportingText,
        errorText = errorText,
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
    errorText: String? = null,
    enabled: Boolean = true
) {
    NexaUnderlinedField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIconRes = R.drawable.ic_lock,
        errorText = errorText,
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
            val description = if (visible) hideLabel else showLabel
            IconButton(
                onClick = onVisibilityChange,
                enabled = enabled,
                modifier = Modifier.size(NexaSizes.minimumTouchTarget).semantics {
                    contentDescription = description
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
private fun NexaUnderlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIconRes: Int,
    supportingText: String? = null,
    errorText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val indicatorColor = when {
        errorText != null -> NexaColors.DangerBorder
        focused -> NexaColors.Primary
        else -> NexaColors.BorderStrong
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NexaSpacing.micro)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = NexaColors.TextSecondary
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = label
                    if (errorText != null) error(errorText)
                },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = NexaColors.TextPrimary),
            enabled = enabled,
            singleLine = true,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            cursorBrush = SolidColor(NexaColors.Primary),
            decorationBox = { innerTextField ->
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = NexaSizes.controlMinHeight),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(leadingIconRes),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (focused) NexaColors.Primary else NexaColors.TextMuted
                        )
                        Spacer(Modifier.width(NexaSpacing.compact))
                        Box(modifier = Modifier.weight(1f)) { innerTextField() }
                        trailingIcon?.invoke()
                    }
                    HorizontalDivider(
                        thickness = if (focused ||
                            errorText != null
                        ) {
                            2.dp
                        } else {
                            NexaSizes.borderWidth
                        },
                        color = indicatorColor
                    )
                }
            }
        )
        val support = errorText ?: supportingText
        if (support != null) {
            Text(
                support,
                style = MaterialTheme.typography.bodySmall,
                color = if (errorText != null) NexaColors.Danger else NexaColors.TextSecondary
            )
        }
    }
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
        shape = NexaShapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = NexaColors.Primary,
            contentColor = NexaColors.OnPrimary,
            disabledContainerColor = if (loading) NexaColors.Primary else NexaColors.Border,
            disabledContentColor = if (loading) NexaColors.OnPrimary else NexaColors.TextSecondary
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = NexaSpacing.large),
                style = MaterialTheme.typography.labelLarge
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterStart).size(NexaSizes.buttonProgress),
                    color = NexaColors.OnPrimary,
                    strokeWidth = 2.dp
                )
            }
        }
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
                if (enabled && !pending) role = Role.Button
                contentDescription = desc
                if (pendingStateDescription != null) {
                    stateDescription = pendingStateDescription
                }
            },
        enabled = enabled && !pending,
        shape = NexaShapes.row,
        color = if (current) NexaColors.PrimaryContainer else NexaColors.Surface,
        border = BorderStroke(
            NexaSizes.borderWidth,
            if (current) NexaColors.Primary else NexaColors.Border
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
                    style = MaterialTheme.typography.titleMedium,
                    color = NexaColors.TextPrimary
                )
                Text(
                    workspaceName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexaColors.TextSecondary
                )
                if (current) {
                    Text(
                        stringResource(R.string.nexa_actual),
                        style = MaterialTheme.typography.labelMedium,
                        color = NexaColors.PrimaryStrong
                    )
                }
            }
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(NexaSizes.progress),
                    color = NexaColors.Primary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    painter = painterResource(
                        if (current) R.drawable.ic_check else R.drawable.ic_chevron_right
                    ),
                    contentDescription = null,
                    tint = if (current) NexaColors.PrimaryStrong else NexaColors.TextSecondary
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
        shape = NexaShapes.card,
        color = NexaColors.Surface,
        border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = NexaSpacing.screenHorizontal,
                vertical = NexaSpacing.surfaceInset
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexaSpacing.compact)
        ) {
            Surface(
                modifier = Modifier.size(NexaSizes.minimumTouchTarget),
                shape = NexaShapes.control,
                color = NexaColors.InfoSurface
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        tint = NexaColors.PrimaryStrong
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NexaSpacing.textCompact)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexaColors.TextSecondary
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = NexaColors.PrimaryStrong
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
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = NexaColors.TextSecondary
            )
        },
        supportingText = errorText?.let { { Text(it) } },
        isError = errorText != null,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = NexaShapes.control,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NexaColors.Primary,
            unfocusedBorderColor = NexaColors.BorderStrong,
            errorBorderColor = NexaColors.DangerBorder,
            focusedContainerColor = NexaColors.Surface,
            unfocusedContainerColor = NexaColors.Surface,
            errorContainerColor = NexaColors.Surface,
            cursorColor = NexaColors.Primary
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onImeSearch() })
    )
}

@Composable
fun NexaProductCandidateRow(
    name: String,
    variant: String?,
    presentation: String?,
    sku: String,
    modifier: Modifier = Modifier,
    pending: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    imageFileName: String? = null
) {
    val candidateDetails = listOfNotNull(
        name,
        variant?.takeIf { it.isNotBlank() },
        presentation?.takeIf { it.isNotBlank() },
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
        border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = NexaSpacing.screenHorizontal,
                vertical = NexaSpacing.candidateRowVertical
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexaSpacing.inline)
        ) {
            NexaCatalogImage(
                fileName = imageFileName,
                modifier = Modifier.size(NexaSizes.catalogImage)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NexaSpacing.textCompact)
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    color = NexaColors.TextPrimary
                )
                if (!variant.isNullOrBlank()) {
                    Text(
                        variant,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexaColors.TextSecondary
                    )
                }
                if (!presentation.isNullOrBlank()) {
                    Text(
                        presentation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexaColors.TextSecondary
                    )
                }
                Text(
                    stringResource(R.string.nexa_candidate_sku, sku),
                    style = NexaTypography.identifier,
                    color = NexaColors.TextSecondary
                )
            }
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(NexaSizes.progress),
                    color = NexaColors.Primary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = NexaColors.TextSecondary
                )
            }
        }
    }
}

/** Displays a bundled canonical catalog image; unknown server keys render no image. */
@Composable
fun NexaCatalogImage(
    fileName: String?,
    modifier: Modifier = Modifier,
    targetSize: Dp = NexaSizes.catalogImage
) {
    val imageResId = CatalogImageRegistry.resolve(fileName) ?: return
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val resources = remember(context, configuration) {
        context.createConfigurationContext(configuration).resources
    }
    val targetSizePx =
        with(LocalDensity.current) { targetSize.toPx().roundToInt().coerceAtLeast(1) }
    val bitmap by produceState<Bitmap?>(initialValue = null, imageResId, targetSizePx) {
        value = withContext(Dispatchers.IO) {
            CatalogImageBitmapLoader.load(
                resources = resources,
                resourceId = imageResId,
                targetWidthPx = targetSizePx,
                targetHeightPx = targetSizePx
            )
        }
    }
    Box(
        modifier = modifier
            .clip(NexaShapes.control)
            .background(NexaColors.SurfaceInset),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun NexaFeedbackBanner(message: String, tone: NexaFeedbackTone, modifier: Modifier = Modifier) {
    val palette = when (tone) {
        NexaFeedbackTone.Information -> NexaFeedbackPalette(
            R.string.nexa_status_information,
            NexaColors.InfoSurface,
            NexaColors.InfoBorder,
            NexaColors.Info
        )

        NexaFeedbackTone.Success -> NexaFeedbackPalette(
            R.string.nexa_status_success,
            NexaColors.SuccessSurface,
            NexaColors.SuccessBorder,
            NexaColors.Success
        )

        NexaFeedbackTone.Warning -> NexaFeedbackPalette(
            R.string.nexa_status_warning,
            NexaColors.WarningSurface,
            NexaColors.WarningBorder,
            NexaColors.Warning
        )

        NexaFeedbackTone.Error -> NexaFeedbackPalette(
            R.string.nexa_status_error,
            NexaColors.DangerSurface,
            NexaColors.DangerBorder,
            NexaColors.Danger
        )
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = NexaShapes.card,
        color = palette.background,
        border = BorderStroke(NexaSizes.borderWidth, palette.border)
    ) {
        Row(
            modifier = Modifier.padding(NexaSpacing.surfaceInset),
            horizontalArrangement = Arrangement.spacedBy(NexaSpacing.compact),
            verticalAlignment = Alignment.Top
        ) {
            NexaToneIcon(tone, palette.foreground)
            Column(verticalArrangement = Arrangement.spacedBy(NexaSpacing.textCompact)) {
                Text(
                    stringResource(palette.label),
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.foreground
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexaColors.TextPrimary
                )
            }
        }
    }
}

@Composable
private fun NexaToneIcon(tone: NexaFeedbackTone, color: Color) {
    Canvas(Modifier.size(NexaSizes.icon)) {
        val unit = size.width / 24f
        drawCircle(color, radius = 10f * unit, style = Stroke(width = 1.8f * unit))
        when (tone) {
            NexaFeedbackTone.Information -> {
                drawLine(
                    color,
                    Offset(12f * unit, 10f * unit),
                    Offset(12f * unit, 17f * unit),
                    2f * unit
                )
                drawCircle(color, radius = 1.1f * unit, center = Offset(12f * unit, 7f * unit))
            }

            NexaFeedbackTone.Success -> {
                drawLine(
                    color,
                    Offset(6.5f * unit, 12f * unit),
                    Offset(10.5f * unit, 16f * unit),
                    2f * unit
                )
                drawLine(
                    color,
                    Offset(10.5f * unit, 16f * unit),
                    Offset(17.5f * unit, 8f * unit),
                    2f * unit
                )
            }

            NexaFeedbackTone.Warning -> {
                drawLine(
                    color,
                    Offset(12f * unit, 6f * unit),
                    Offset(12f * unit, 13f * unit),
                    2f * unit
                )
                drawCircle(color, radius = 1.1f * unit, center = Offset(12f * unit, 17f * unit))
            }

            NexaFeedbackTone.Error -> {
                drawLine(
                    color,
                    Offset(8f * unit, 8f * unit),
                    Offset(16f * unit, 16f * unit),
                    2f * unit
                )
                drawLine(
                    color,
                    Offset(16f * unit, 8f * unit),
                    Offset(8f * unit, 16f * unit),
                    2f * unit
                )
            }
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
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = NexaColors.TextPrimary
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyLarge,
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
    modifier: Modifier = Modifier,
    coldChainTone: NexaColdChainTone = NexaColdChainTone.Neutral,
    imageFileName: String? = null
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
            NexaCatalogImage(
                fileName = imageFileName,
                modifier = Modifier.size(NexaSizes.catalogDetailImage),
                targetSize = NexaSizes.catalogDetailImage
            )
            Text(
                productName,
                style = MaterialTheme.typography.titleLarge,
                color = NexaColors.TextPrimary
            )
            if (!variant.isNullOrBlank()) {
                AttributeRow(
                    stringResource(R.string.nexa_variant),
                    variant
                )
            }
            AttributeRow(stringResource(R.string.nexa_presentation), presentation)
            AttributeRow(
                stringResource(R.string.nexa_candidate_sku_label),
                sku,
                valueStyle = NexaTypography.identifier
            )
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
                    coldChain,
                    valueColor = when (coldChainTone) {
                        NexaColdChainTone.Neutral -> NexaColors.TextPrimary
                        NexaColdChainTone.Refrigerated -> NexaColors.ColdRefrigeratedText
                        NexaColdChainTone.Frozen -> NexaColors.ColdFrozenText
                    }
                )
            }
            AttributeRow(stringResource(R.string.nexa_context), activeContext)
        }
    }
}

@Composable
private fun AttributeRow(
    label: String,
    value: String,
    valueStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    valueColor: Color = NexaColors.TextPrimary
) {
    Column(verticalArrangement = Arrangement.spacedBy(NexaSpacing.textCompact)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = NexaColors.TextMuted
        )
        Text(value, style = valueStyle, color = valueColor)
    }
}
