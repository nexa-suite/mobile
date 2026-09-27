package com.nexa.mobile.operations.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Color roles align with the mobile token reference; text keeps Android's native sans-serif. */
object NexaColors {
    val Brand = Color(0xFF2A67D9)
    val BrandStrong = Color(0xFF1D4ED8)
    val PrimaryContainer = Color(0xFFDBEAFE)
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

internal object NexaShapes {
    val button = RoundedCornerShape(12.dp)
    val surface = RoundedCornerShape(16.dp)
    val row = RoundedCornerShape(12.dp)
}

private val NexaLightColors = lightColorScheme(
    primary = NexaColors.Brand,
    onPrimary = Color.White,
    primaryContainer = NexaColors.PrimaryContainer,
    onPrimaryContainer = NexaColors.BrandStrong,
    secondary = NexaColors.TextSecondary,
    onSecondary = Color.White,
    background = NexaColors.Canvas,
    onBackground = NexaColors.TextPrimary,
    surface = NexaColors.Surface,
    onSurface = NexaColors.TextPrimary,
    surfaceVariant = NexaColors.SurfaceInset,
    onSurfaceVariant = NexaColors.TextPrimary,
    outline = NexaColors.BorderStrong,
    outlineVariant = NexaColors.Border,
    error = NexaColors.Danger,
    onError = Color.White,
    errorContainer = NexaColors.DangerSurface,
    onErrorContainer = NexaColors.Danger
)

private val DefaultNexaTypography = androidx.compose.material3.Typography()
private val NexaTypography = androidx.compose.material3.Typography(
    headlineSmall = DefaultNexaTypography.headlineSmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 30.sp
    ),
    titleLarge = DefaultNexaTypography.titleLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 26.sp
    ),
    titleMedium = DefaultNexaTypography.titleMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp
    ),
    bodyLarge = DefaultNexaTypography.bodyLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp
    ),
    bodyMedium = DefaultNexaTypography.bodyMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp
    ),
    labelLarge = DefaultNexaTypography.labelLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp
    )
)

@Composable
fun OperationsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NexaLightColors,
        typography = NexaTypography,
        content = content
    )
}
