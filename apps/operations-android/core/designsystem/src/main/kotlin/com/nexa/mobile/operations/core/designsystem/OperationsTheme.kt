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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Frozen Design Lab v1.1.1 semantic colors; brand-only roles stay separate. */
object NexaColors {
    val Primary = Color(0xFF2563EB)
    val PrimaryStrong = Color(0xFF1D4ED8)
    val OnPrimary = Color.White
    val PrimaryContainer = Color(0xFFDBEAFE)
    val OnPrimaryContainer = Color(0xFF1E40AF)
    val Focus = Primary

    // The frozen Mobile Style uses these only on Nexa identity surfaces.
    val BrandNavy = Color(0xFF082846)
    val BrandCeleste = Color(0xFF38C8FF)

    val Canvas = Color(0xFFF6FAFF)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceInset = Color(0xFFF1F5F9)
    val TextPrimary = Color(0xFF0F172A)
    val TextSecondary = Color(0xFF64748B)
    val TextMuted = Color(0xFF94A3B8)
    val Border = Color(0xFFE2E8F0)
    val BorderStrong = Color(0xFFCBD5E1)
    val InfoSurface = Color(0xFFEFF6FF)
    val InfoBorder = Color(0xFF93C5FD)
    val Info = Color(0xFF1D4ED8)
    val SuccessSurface = Color(0xFFF0FDF4)
    val SuccessBorder = Color(0xFFBBF7D0)
    val Success = Color(0xFF15803D)
    val WarningSurface = Color(0xFFFFFBEB)
    val WarningBorder = Color(0xFFFCD34D)
    val Warning = Color(0xFF92400E)
    val DangerSurface = Color(0xFFFEF2F2)
    val DangerBorder = Color(0xFFDC2626)
    val Danger = Color(0xFF991B1B)

    val ColdRefrigerated = Color(0xFF0284C7)
    val ColdRefrigeratedSurface = Color(0xFFF0F9FF)
    val ColdRefrigeratedBorder = Color(0xFFBAE6FD)
    val ColdRefrigeratedText = Color(0xFF075985)
    val ColdFrozen = Color(0xFF4F46E5)
    val ColdFrozenSurface = Color(0xFFEEF2FF)
    val ColdFrozenBorder = Color(0xFFC7D2FE)
    val ColdFrozenText = Color(0xFF3730A3)
}

/** Semantic shapes used by the Operations component set. */
object NexaShapes {
    val control = RoundedCornerShape(10.dp)
    val button = RoundedCornerShape(12.dp)
    val row = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(16.dp)
    val surface = card
    val panel = RoundedCornerShape(18.dp)
    val dialog = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(percent = 50)
}

/** Repeated spacing roles; one-off layout details stay local to their component. */
object NexaSpacing {
    val micro = 4.dp
    val base = 8.dp
    val compact = 12.dp
    val standard = 16.dp
    val comfortable = 20.dp
    val section = 24.dp
    val large = 32.dp
    val hero = 40.dp

    val textTight = 2.dp
    val textCompact = 4.dp
    val topBarVertical = 8.dp
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
    val primaryButtonMinHeight = 56.dp
    val minimumTouchTarget = 48.dp
    val icon = 24.dp
    val buttonProgress = 18.dp
    val progress = 22.dp
    val activeContextMinHeight = 64.dp
    val contextChoiceMinHeight = 72.dp
    val taskRowMinHeight = 80.dp
    val candidateRowMinHeight = 88.dp
}

private val NexaLightColors = lightColorScheme(
    primary = NexaColors.Primary,
    onPrimary = NexaColors.OnPrimary,
    primaryContainer = NexaColors.PrimaryContainer,
    onPrimaryContainer = NexaColors.OnPrimaryContainer,
    secondary = NexaColors.TextSecondary,
    onSecondary = NexaColors.OnPrimary,
    background = NexaColors.Canvas,
    onBackground = NexaColors.TextPrimary,
    surface = NexaColors.Surface,
    onSurface = NexaColors.TextPrimary,
    surfaceVariant = NexaColors.SurfaceInset,
    onSurfaceVariant = NexaColors.TextSecondary,
    outline = NexaColors.BorderStrong,
    outlineVariant = NexaColors.Border,
    error = NexaColors.DangerBorder,
    onError = NexaColors.OnPrimary,
    errorContainer = NexaColors.DangerSurface,
    onErrorContainer = NexaColors.Danger
)

/** Typography roles consumed by OperationsTheme and reusable components. */
object NexaTypography {
    private val defaults = Typography()
    private val display = FontFamily(
        Font(R.font.plus_jakarta_sans_semibold, weight = FontWeight.SemiBold),
        Font(R.font.plus_jakarta_sans_bold, weight = FontWeight.Bold)
    )
    private val body = FontFamily(
        Font(R.font.inter_regular, weight = FontWeight.Normal),
        Font(R.font.inter_semibold, weight = FontWeight.SemiBold)
    )

    val headlineLarge = defaults.headlineLarge.copy(
        fontFamily = display,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 36.sp
    )

    val headlineSmall = defaults.headlineSmall.copy(
        fontFamily = display,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 32.sp
    )
    val titleLarge = defaults.titleLarge.copy(
        fontFamily = display,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 28.sp
    )
    val titleMedium = defaults.titleMedium.copy(
        fontFamily = display,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp
    )
    val titleSmall = defaults.titleSmall.copy(
        fontFamily = display,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp
    )
    val bodyLarge = defaults.bodyLarge.copy(
        fontFamily = body,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp
    )
    val bodyMedium = defaults.bodyMedium.copy(
        fontFamily = body,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp
    )
    val bodySmall = defaults.bodySmall.copy(
        fontFamily = body,
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp
    )
    val labelLarge = defaults.labelLarge.copy(
        fontFamily = body,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp
    )
    val labelMedium = defaults.labelMedium.copy(
        fontFamily = body,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 18.sp
    )
    val labelSmall = defaults.labelSmall.copy(
        fontFamily = body,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 16.sp
    )
    val identifier = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 18.sp
    )

    val material = Typography(
        headlineLarge = headlineLarge,
        headlineSmall = headlineSmall,
        titleLarge = titleLarge,
        titleMedium = titleMedium,
        titleSmall = titleSmall,
        bodyLarge = bodyLarge,
        bodyMedium = bodyMedium,
        bodySmall = bodySmall,
        labelLarge = labelLarge,
        labelMedium = labelMedium,
        labelSmall = labelSmall
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
