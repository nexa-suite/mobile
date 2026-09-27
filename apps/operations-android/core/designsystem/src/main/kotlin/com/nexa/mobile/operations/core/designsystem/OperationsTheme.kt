package com.nexa.mobile.operations.core.designsystem

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Color roles align with the mobile token reference; text keeps Android's native sans-serif. */
object NexaColors {
    val Brand = Color(0xFF2A67D9)
    val BrandStrong = Color(0xFF1D4ED8)
    val OnBrand = Color.White
    val PrimaryContainer = Color(0xFFDBEAFE)
    val OnPrimaryContainer = Color(0xFF1E40AF)
    val Canvas = Color(0xFFF6FAFF)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceInset = Color(0xFFF1F5F9)
    val TextPrimary = Color(0xFF0F172A)
    val TextSecondary = Color(0xFF64748B)
    val TextMuted = Color(0xFF64748B)
    val Border = Color(0xFFE2E8F0)
    val BorderStrong = Color(0xFFCBD5E1)
    val InfoSurface = Color(0xFFEEF6FF)
    val Info = BrandStrong
    val SuccessSurface = Color(0xFFF0FDF4)
    val Success = Color(0xFF15803D)
    val WarningSurface = Color(0xFFFFFBEB)
    val Warning = Color(0xFF92400E)
    val DangerSurface = Color(0xFFFEF2F2)
    val Danger = Color(0xFF991B1B)
}

/** Semantic shapes used by the Operations component set. */
object NexaShapes {
    val button = RoundedCornerShape(12.dp)
    val surface = RoundedCornerShape(16.dp)
    val row = RoundedCornerShape(12.dp)
}

/** Repeated spacing roles; one-off layout details stay local to their component. */
object NexaSpacing {
    val textTight = 2.dp
    val textCompact = 4.dp
    val topBarVertical = 8.dp
    val buttonIndicatorGap = 10.dp
    val inline = 12.dp
    val rowVertical = 12.dp
    val candidateRowVertical = 14.dp
    val screenHorizontal = 16.dp
    val surfaceInset = 16.dp
    val summaryInset = 20.dp
}

/** Shared component dimensions and touch-target sizes. */
object NexaSizes {
    val borderWidth = 1.dp
    val topBarMinHeight = 56.dp
    val controlMinHeight = 56.dp
    val primaryButtonMinHeight = 52.dp
    val minimumTouchTarget = 48.dp
    val icon = 24.dp
    val buttonProgress = 18.dp
    val progress = 22.dp
    val contextChoiceMinHeight = 72.dp
    val taskRowMinHeight = 80.dp
    val candidateRowMinHeight = 88.dp
}

private val NexaLightColors = lightColorScheme(
    primary = NexaColors.Brand,
    onPrimary = NexaColors.OnBrand,
    primaryContainer = NexaColors.PrimaryContainer,
    onPrimaryContainer = NexaColors.OnPrimaryContainer,
    secondary = NexaColors.TextSecondary,
    onSecondary = NexaColors.OnBrand,
    background = NexaColors.Canvas,
    onBackground = NexaColors.TextPrimary,
    surface = NexaColors.Surface,
    onSurface = NexaColors.TextPrimary,
    surfaceVariant = NexaColors.SurfaceInset,
    onSurfaceVariant = NexaColors.TextPrimary,
    outline = NexaColors.BorderStrong,
    outlineVariant = NexaColors.Border,
    error = NexaColors.Danger,
    onError = NexaColors.OnBrand,
    errorContainer = NexaColors.DangerSurface,
    onErrorContainer = NexaColors.Danger
)

/** Typography roles consumed by OperationsTheme and reusable components. */
object NexaTypography {
    private val defaults = Typography()

    val headlineSmall = defaults.headlineSmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 32.sp
    )
    val titleLarge = defaults.titleLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp
    )
    val titleMedium = defaults.titleMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp
    )
    val bodyLarge = defaults.bodyLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp
    )
    val bodyMedium = defaults.bodyMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp
    )
    val labelLarge = defaults.labelLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 20.sp
    )
    val backGlyph = TextStyle(fontSize = 28.sp)

    val material = Typography(
        headlineSmall = headlineSmall,
        titleLarge = titleLarge,
        titleMedium = titleMedium,
        bodyLarge = bodyLarge,
        bodyMedium = bodyMedium,
        labelLarge = labelLarge
    )
}

@Composable
fun OperationsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NexaLightColors,
        typography = NexaTypography.material,
        content = {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = NexaColors.Canvas,
                contentColor = NexaColors.TextPrimary,
                content = content
            )
        }
    )
}
