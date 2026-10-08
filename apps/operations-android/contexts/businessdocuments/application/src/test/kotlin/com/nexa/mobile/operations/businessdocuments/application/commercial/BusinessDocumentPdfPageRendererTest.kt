package com.nexa.mobile.operations.businessdocuments.application.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BusinessDocumentPdfPageRendererTest {
    @Test
    fun renderedPageAcceptsBoundedPixelPayload() {
        val pixels = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt())

        val page = RenderedBusinessDocumentPage(2, 1, pixels, totalPages = 1)

        assertEquals(2, page.width)
        assertEquals(1, page.height)
        assertEquals(pixels.toList(), page.argbPixels.toList())
    }

    @Test
    fun renderedPageRejectsInconsistentOrUnboundedPixelPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            RenderedBusinessDocumentPage(2, 1, intArrayOf(1), totalPages = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RenderedBusinessDocumentPage(1600, 10_000, intArrayOf(), totalPages = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RenderedBusinessDocumentPage(1, 1, intArrayOf(1), totalPages = 0)
        }
    }
}
