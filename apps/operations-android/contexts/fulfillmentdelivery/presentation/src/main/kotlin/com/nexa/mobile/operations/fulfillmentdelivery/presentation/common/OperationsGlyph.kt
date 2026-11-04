package com.nexa.mobile.operations.fulfillmentdelivery.presentation.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors

internal enum class OperationsGlyphKind { Picking, Dispatch, Driver, Proof }

@Composable
internal fun OperationsGlyph(kind: OperationsGlyphKind, modifier: Modifier = Modifier) {
    val tint = when (kind) {
        OperationsGlyphKind.Picking -> NexaColors.PrimaryStrong
        OperationsGlyphKind.Dispatch -> NexaColors.Info
        OperationsGlyphKind.Driver -> NexaColors.PrimaryStrong
        OperationsGlyphKind.Proof -> NexaColors.Success
    }
    val surface = when (kind) {
        OperationsGlyphKind.Picking -> NexaColors.InfoSurface
        OperationsGlyphKind.Dispatch -> NexaColors.InfoSurface
        OperationsGlyphKind.Driver -> NexaColors.SurfaceInset
        OperationsGlyphKind.Proof -> NexaColors.SuccessSurface
    }
    Surface(
        modifier = modifier.size(44.dp),
        shape = RoundedCornerShape(12.dp),
        color = surface
    ) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val width = size.width
            val height = size.height
            val stroke = Stroke(
                width = width * 0.09f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
            fun point(x: Float, y: Float) = Offset(width * x, height * y)
            when (kind) {
                OperationsGlyphKind.Picking -> {
                    drawRoundRect(
                        tint,
                        topLeft = point(0.08f, 0.08f),
                        size = androidx.compose.ui.geometry.Size(width * 0.84f, height * 0.84f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.12f),
                        style = stroke
                    )
                    drawLine(
                        tint,
                        point(0.27f, 0.35f),
                        point(0.39f, 0.46f),
                        stroke.width,
                        StrokeCap.Round
                    )
                    drawLine(
                        tint,
                        point(0.39f, 0.46f),
                        point(0.56f, 0.26f),
                        stroke.width,
                        StrokeCap.Round
                    )
                    drawLine(
                        tint,
                        point(0.27f, 0.68f),
                        point(0.70f, 0.68f),
                        stroke.width,
                        StrokeCap.Round
                    )
                }

                OperationsGlyphKind.Dispatch -> {
                    drawRoundRect(
                        tint,
                        topLeft = point(0.08f, 0.27f),
                        size = androidx.compose.ui.geometry.Size(width * 0.52f, height * 0.43f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.06f),
                        style = stroke
                    )
                    drawPath(
                        Path().apply {
                            moveTo(width * 0.60f, height * 0.39f)
                            lineTo(width * 0.78f, height * 0.39f)
                            lineTo(width * 0.92f, height * 0.55f)
                            lineTo(width * 0.92f, height * 0.70f)
                            lineTo(width * 0.60f, height * 0.70f)
                            close()
                        },
                        tint,
                        style = stroke
                    )
                    drawCircle(tint, width * 0.11f, point(0.29f, 0.77f))
                    drawCircle(tint, width * 0.11f, point(0.76f, 0.77f))
                }

                OperationsGlyphKind.Driver -> {
                    drawPath(
                        Path().apply {
                            moveTo(width * 0.18f, height * 0.77f)
                            lineTo(width * 0.43f, height * 0.52f)
                            lineTo(width * 0.66f, height * 0.52f)
                            lineTo(width * 0.83f, height * 0.26f)
                        },
                        tint,
                        style = stroke
                    )
                    drawCircle(tint, width * 0.11f, point(0.18f, 0.77f))
                    drawCircle(tint, width * 0.11f, point(0.43f, 0.52f))
                    drawCircle(tint, width * 0.11f, point(0.66f, 0.52f))
                    drawCircle(tint, width * 0.11f, point(0.83f, 0.26f))
                }

                OperationsGlyphKind.Proof -> {
                    drawRoundRect(
                        tint,
                        topLeft = point(0.19f, 0.07f),
                        size = androidx.compose.ui.geometry.Size(width * 0.62f, height * 0.86f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.09f),
                        style = stroke
                    )
                    drawLine(
                        tint,
                        point(0.32f, 0.52f),
                        point(0.45f, 0.65f),
                        stroke.width,
                        StrokeCap.Round
                    )
                    drawLine(
                        tint,
                        point(0.45f, 0.65f),
                        point(0.70f, 0.35f),
                        stroke.width,
                        StrokeCap.Round
                    )
                }
            }
        }
    }
}
