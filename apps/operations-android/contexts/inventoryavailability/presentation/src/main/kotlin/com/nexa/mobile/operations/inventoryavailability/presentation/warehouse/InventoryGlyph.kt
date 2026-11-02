package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors

internal enum class InventoryGlyphKind { Product, Warehouse, Receipt }

@Composable
internal fun InventoryGlyph(kind: InventoryGlyphKind, modifier: Modifier = Modifier) {
    val tint = when (kind) {
        InventoryGlyphKind.Product -> NexaColors.PrimaryStrong
        InventoryGlyphKind.Warehouse -> NexaColors.Info
        InventoryGlyphKind.Receipt -> NexaColors.Success
    }
    val container = when (kind) {
        InventoryGlyphKind.Product -> NexaColors.InfoSurface
        InventoryGlyphKind.Warehouse -> NexaColors.SurfaceInset
        InventoryGlyphKind.Receipt -> NexaColors.SuccessSurface
    }
    Surface(
        modifier = modifier.size(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = container
    ) {
        Canvas(Modifier.fillMaxSize().padding(9.dp)) {
            val stroke = Stroke(size.width * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
            when (kind) {
                InventoryGlyphKind.Product -> {
                    drawPath(
                        Path().apply {
                            moveTo(size.width * 0.5f, size.height * 0.08f)
                            lineTo(size.width * 0.9f, size.height * 0.3f)
                            lineTo(size.width * 0.5f, size.height * 0.52f)
                            lineTo(size.width * 0.1f, size.height * 0.3f)
                            close()
                        },
                        tint,
                        style = stroke
                    )
                    drawLine(tint, point(0.1f, 0.3f), point(0.1f, 0.7f), stroke.width)
                    drawLine(tint, point(0.9f, 0.3f), point(0.9f, 0.7f), stroke.width)
                    drawLine(tint, point(0.1f, 0.7f), point(0.5f, 0.92f), stroke.width)
                    drawLine(tint, point(0.9f, 0.7f), point(0.5f, 0.92f), stroke.width)
                    drawLine(tint, point(0.5f, 0.52f), point(0.5f, 0.92f), stroke.width)
                }

                InventoryGlyphKind.Warehouse -> {
                    drawPath(
                        Path().apply {
                            moveTo(size.width * 0.08f, size.height * 0.42f)
                            lineTo(size.width * 0.5f, size.height * 0.1f)
                            lineTo(size.width * 0.92f, size.height * 0.42f)
                        },
                        tint,
                        style = stroke
                    )
                    drawRoundRect(
                        tint,
                        topLeft = point(0.16f, 0.4f),
                        size = Size(size.width * 0.68f, size.height * 0.5f),
                        cornerRadius = CornerRadius(size.width * 0.04f),
                        style = stroke
                    )
                    drawLine(tint, point(0.38f, 0.9f), point(0.38f, 0.62f), stroke.width)
                    drawLine(tint, point(0.62f, 0.9f), point(0.62f, 0.62f), stroke.width)
                }

                InventoryGlyphKind.Receipt -> {
                    drawRoundRect(
                        tint,
                        topLeft = point(0.16f, 0.08f),
                        size = Size(size.width * 0.68f, size.height * 0.84f),
                        cornerRadius = CornerRadius(size.width * 0.08f),
                        style = stroke
                    )
                    drawLine(
                        tint,
                        point(0.3f, 0.36f),
                        point(0.42f, 0.48f),
                        stroke.width,
                        StrokeCap.Round
                    )
                    drawLine(
                        tint,
                        point(0.42f, 0.48f),
                        point(0.68f, 0.25f),
                        stroke.width,
                        StrokeCap.Round
                    )
                    drawLine(
                        tint,
                        point(0.31f, 0.67f),
                        point(0.68f, 0.67f),
                        stroke.width,
                        StrokeCap.Round
                    )
                }
            }
        }
    }
}
