package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R

/** Encodes only the opaque token already issued by the server. Never stores it. */
@Composable
internal fun DeliveryHandoffQr(token: String, modifier: Modifier = Modifier) {
    val matrix = remember(token) {
        runCatching {
            QRCodeWriter().encode(
                token,
                BarcodeFormat.QR_CODE,
                1,
                1,
                mapOf(EncodeHintType.MARGIN to 4)
            )
        }.getOrNull()
    } ?: return
    val description = stringResource(R.string.driver_handoff_qr_description)
    Canvas(
        modifier.fillMaxWidth().aspectRatio(1f).background(Color.White)
            .semantics { contentDescription = description }
    ) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
                }
            }
        }
    }
}
