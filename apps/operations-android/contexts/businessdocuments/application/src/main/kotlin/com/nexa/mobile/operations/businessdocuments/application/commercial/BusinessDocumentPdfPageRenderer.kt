package com.nexa.mobile.operations.businessdocuments.application.commercial

interface BusinessDocumentPdfPageRenderer {
    suspend fun renderPage(pdfBytes: ByteArray, pageIndex: Int): RenderedBusinessDocumentPage?
}

class RenderedBusinessDocumentPage(
    val width: Int,
    val height: Int,
    val argbPixels: IntArray,
    val totalPages: Int
) {
    init {
        val pixelCount = width.toLong() * height
        require(width in 1..MAX_WIDTH && height in 1..MAX_HEIGHT)
        require(pixelCount in 1..MAX_PIXEL_COUNT.toLong())
        require(argbPixels.size.toLong() == pixelCount)
        require(totalPages > 0)
    }

    companion object {
        const val MAX_WIDTH = 1600
        const val MAX_HEIGHT = 10_000
        const val MAX_PIXEL_COUNT = 2_097_152
    }
}
