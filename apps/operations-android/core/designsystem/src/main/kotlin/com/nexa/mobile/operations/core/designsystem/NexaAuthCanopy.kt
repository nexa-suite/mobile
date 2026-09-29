package com.nexa.mobile.operations.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** A brand-only plane. Authentication state and controls belong to its caller. */
@Composable
fun NexaAuthCanopy(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier.clipToBounds().drawWithCache {
            val width = size.width
            val height = size.height
            val contourStroke = 1.2.dp.toPx()
            val glow = Brush.radialGradient(
                colors = listOf(NexaColors.PrimaryStrong.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(width * 0.52f, height * 0.24f),
                radius = width * 0.8f
            )
            val contourRings = listOf(
                0.09f to 0.09f,
                0.17f to 0.18f,
                0.26f to 0.28f,
                0.36f to 0.39f,
                0.47f to 0.52f,
                0.60f to 0.66f,
                0.73f to 0.81f
            ).mapIndexed { index, (radiusX, radiusY) ->
                val centerX = width * (0.56f + index * 0.003f)
                val centerY = height * (0.36f - index * 0.004f)
                Path().apply {
                    addOval(
                        Rect(
                            centerX - width * radiusX,
                            centerY - height * radiusY,
                            centerX + width * radiusX,
                            centerY + height * radiusY
                        )
                    )
                }
            }
            val secondaryRing = Path().apply {
                addOval(
                    Rect(
                        width * 0.02f,
                        height * 0.56f,
                        width * 0.34f,
                        height * 0.88f
                    )
                )
            }
            val waveHeight = 52.dp.toPx()
            val waveTop = height - waveHeight
            val wave = Path().apply {
                moveTo(0f, waveTop + waveHeight * 25f / 60f)
                cubicTo(
                    width * 95f / 390f,
                    waveTop + waveHeight * 50f / 60f,
                    width * 170f / 390f,
                    waveTop + waveHeight * 55f / 60f,
                    width * 240f / 390f,
                    waveTop + waveHeight * 30f / 60f
                )
                cubicTo(
                    width * 300f / 390f,
                    waveTop + waveHeight * 8f / 60f,
                    width * 350f / 390f,
                    waveTop + waveHeight * 12f / 60f,
                    width,
                    waveTop + waveHeight * 30f / 60f
                )
                lineTo(width, height)
                lineTo(0f, height)
                close()
            }
            onDrawBehind {
                drawRect(NexaColors.BrandNavy)
                drawRect(glow)
                contourRings.forEachIndexed { index, ring ->
                    drawPath(
                        ring,
                        NexaColors.BrandCeleste.copy(alpha = 0.22f - index * 0.012f),
                        style = Stroke(width = contourStroke)
                    )
                }
                drawPath(
                    secondaryRing,
                    NexaColors.BrandCeleste.copy(alpha = 0.14f),
                    style = Stroke(width = contourStroke)
                )
                drawPath(wave, NexaColors.Surface)
            }
        },
        content = content
    )
}
