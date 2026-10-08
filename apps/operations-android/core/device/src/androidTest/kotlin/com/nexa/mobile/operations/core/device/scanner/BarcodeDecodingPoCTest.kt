package com.nexa.mobile.operations.core.device.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Decoder-only PoC. These static images do not exercise CameraX or camera hardware. */
@RunWith(AndroidJUnit4::class)
class BarcodeDecodingPoCTest {
    @Test
    fun syntheticQrAndEan13ImagesDecodeToRawCandidates() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val scanner = scanner()

        try {
            assertDecoded(
                context = context,
                scanner = scanner,
                assetPath = "fixtures/synthetic-qr.png",
                expectedValue = QR_VALUE,
                expectedFormat = Barcode.FORMAT_QR_CODE
            )
            assertDecoded(
                context = context,
                scanner = scanner,
                assetPath = "fixtures/synthetic-ean13.png",
                expectedValue = EAN13_VALUE,
                expectedFormat = Barcode.FORMAT_EAN_13
            )
        } finally {
            scanner.close()
        }
    }

    @Test
    fun blankImageProducesNoDecodedCandidate() {
        val scanner = scanner()
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)

        try {
            val decoded = Tasks.await(
                scanner.process(InputImage.fromBitmap(bitmap, 0)),
                DECODE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )

            assertTrue("A non-barcode image must produce no decoded value", decoded.isEmpty())
        } finally {
            bitmap.recycle()
            scanner.close()
        }
    }

    private fun scanner(): BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_EAN_13)
            .build()
    )

    private fun assertDecoded(
        context: Context,
        scanner: BarcodeScanner,
        assetPath: String,
        expectedValue: String,
        expectedFormat: Int
    ) {
        val bitmap = context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
            ?: error("Missing test bitmap asset: $assetPath")

        try {
            val decoded = Tasks.await(
                scanner.process(InputImage.fromBitmap(bitmap, 0)),
                DECODE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )

            assertEquals("Expected one decoded barcode in $assetPath", 1, decoded.size)
            assertEquals(expectedValue, decoded.single().rawValue)
            assertEquals(expectedFormat, decoded.single().format)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val DECODE_TIMEOUT_SECONDS = 30L
        const val EAN13_VALUE = "1234567890128"
        const val QR_VALUE = "NEXA-SYNTHETIC-PRODUCT-042"
    }
}
