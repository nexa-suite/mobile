package com.nexa.mobile.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val NexaBlue = Color(0xFF125CFA)
private val NexaCanvas = Color(0xFFF6FAFF)
private val NexaSlate50 = Color(0xFFF8FAFC)
private val NexaSlate200 = Color(0xFFE2E8F0)
private val NexaSlate300 = Color(0xFFCBD5E1)
private val NexaSlate600 = Color(0xFF475569)
private val NexaSlate900 = Color(0xFF0F172A)
private val NexaGreen50 = Color(0xFFF0FDF4)
private val NexaGreen200 = Color(0xFFBBF7D0)
private val NexaGreen700 = Color(0xFF15803D)
private val NexaAmber50 = Color(0xFFFFFBEB)
private val NexaAmber200 = Color(0xFFFCD34D)
private val NexaAmber800 = Color(0xFF92400E)
private val NexaRed50 = Color(0xFFFEF2F2)
private val NexaRed200 = Color(0xFFFECACA)
private val NexaRed800 = Color(0xFF991B1B)

private val NexaLightColorScheme = lightColorScheme(
    primary = NexaBlue,
    onPrimary = Color.White,
    background = NexaCanvas,
    onBackground = NexaSlate900,
    surface = Color.White,
    onSurface = NexaSlate900,
    surfaceVariant = NexaSlate50,
    onSurfaceVariant = NexaSlate600,
    outline = NexaSlate300,
    outlineVariant = NexaSlate200,
    error = NexaRed800,
    onError = Color.White,
    errorContainer = NexaRed50,
    onErrorContainer = NexaRed800,
)

private val NexaShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
)

enum class NexaStatusTone {
    INFO,
    SUCCESS,
    WARNING,
    DANGER,
}

@Composable
fun NexaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NexaLightColorScheme,
        typography = Typography(),
        shapes = NexaShapes,
        content = content,
    )
}

@Composable
fun NexaStatusBanner(
    message: String,
    tone: NexaStatusTone,
    modifier: Modifier = Modifier,
) {
    val (containerColor, contentColor, borderColor) = when (tone) {
        NexaStatusTone.INFO -> Triple(
            NexaLightColorScheme.primary.copy(alpha = 0.08f),
            NexaLightColorScheme.primary,
            NexaLightColorScheme.primary.copy(alpha = 0.28f),
        )
        NexaStatusTone.SUCCESS -> Triple(NexaGreen50, NexaGreen700, NexaGreen200)
        NexaStatusTone.WARNING -> Triple(NexaAmber50, NexaAmber800, NexaAmber200)
        NexaStatusTone.DANGER -> Triple(NexaRed50, NexaRed800, NexaRed200)
    }

    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(contentColor),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
