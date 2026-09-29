package com.nexa.mobile.operations.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Frozen Design Lab/Web auth grid and Mobile Style wave, drawn as native decoration. */
@Composable
fun NexaAuthCanopy(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier.clipToBounds().drawWithCache {
            val width = size.width
            val height = size.height
            val gridStep = 32.dp.toPx()
            val gridStroke = 1.dp.toPx()
            val gridColor = Color.White.copy(alpha = 0.10f)
            val gridX = generateSequence(0f) { it + gridStep }.takeWhile { it <= width }.toList()
            val gridY = generateSequence(0f) { it + gridStep }.takeWhile { it <= height }.toList()
            val gradient = Brush.linearGradient(
                colors = listOf(
                    NexaColors.PrimaryDeep,
                    NexaColors.BrandNavy,
                    NexaColors.PrimaryStrong
                ),
                start = Offset.Zero,
                end = Offset(width, height * 1.15f)
            )
            val markSize = 32.dp.toPx()
            val markGap = 8.dp.toPx()
            val markX = width - 24.dp.toPx() - markSize * 2 - markGap
            val markY = height * 0.51f
            val waveHeight = 66.dp.toPx()
            val waveTop = height - waveHeight
            val wave = Path().apply {
                moveTo(0f, waveTop + waveHeight * 0.45f)
                cubicTo(
                    width * 0.15f,
                    waveTop + waveHeight * 0.70f,
                    width * 0.35f,
                    waveTop + waveHeight * 0.84f,
                    width * 0.51f,
                    waveTop + waveHeight * 0.67f
                )
                cubicTo(
                    width * 0.70f,
                    waveTop + waveHeight * 0.45f,
                    width * 0.77f,
                    waveTop + waveHeight * 0.12f,
                    width,
                    waveTop + waveHeight * 0.41f
                )
                lineTo(width, height)
                lineTo(0f, height)
                close()
            }
            onDrawBehind {
                drawRect(gradient)
                gridX.forEach { x ->
                    drawLine(gridColor, Offset(x, 0f), Offset(x, height), gridStroke)
                }
                gridY.forEach { y ->
                    drawLine(gridColor, Offset(0f, y), Offset(width, y), gridStroke)
                }
                repeat(2) { row ->
                    repeat(2) { column ->
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.36f),
                            topLeft = Offset(
                                markX + column * (markSize + markGap),
                                markY + row * (markSize + markGap)
                            ),
                            size = androidx.compose.ui.geometry.Size(markSize, markSize),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                            style = Stroke(width = gridStroke)
                        )
                    }
                }
                drawPath(wave, NexaColors.Surface)
            }
        },
        content = content
    )
}
