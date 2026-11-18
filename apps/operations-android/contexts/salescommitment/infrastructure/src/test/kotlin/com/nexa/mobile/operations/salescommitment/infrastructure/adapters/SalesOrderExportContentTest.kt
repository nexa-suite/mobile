package com.nexa.mobile.operations.salescommitment.infrastructure.adapters

import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesOrderExportContentTest {
    @Test
    fun acceptsOnlyBoundedSuccessfulContentWithTheRequestedMediaTypeAndCopiesIt() {
        val source = byteArrayOf(1, 2, 3)
        val result =
            salesExportContent(
                success(200, "application/pdf; charset=binary", source),
                SalesOrderExportFormat.PDF
            )

        assertArrayEquals(byteArrayOf(1, 2, 3), result)
        assertArrayEquals(byteArrayOf(0, 0, 0), source)
        result?.fill(0)
    }

    @Test
    fun rejectsWrongStatusTypeEmptyAndOversizedBodiesAndClearsRejectedBytes() {
        val wrongType = byteArrayOf(4, 5)
        val empty = byteArrayOf()
        val tooLarge = ByteArray(8 * 1024 * 1024 + 1) { 7 }

        assertNull(
            salesExportContent(
                success(201, "application/pdf", byteArrayOf(1)),
                SalesOrderExportFormat.PDF
            )
        )
        assertNull(
            salesExportContent(success(200, "text/csv", wrongType), SalesOrderExportFormat.PDF)
        )
        assertNull(
            salesExportContent(success(200, "application/pdf", empty), SalesOrderExportFormat.PDF)
        )
        assertNull(
            salesExportContent(
                success(200, "application/pdf", tooLarge),
                SalesOrderExportFormat.PDF
            )
        )

        assertArrayEquals(byteArrayOf(0, 0), wrongType)
        assertTrue(tooLarge.all { it == 0.toByte() })
    }

    @Test
    fun csvRequiresItsExactRequestedContentType() {
        val accepted = byteArrayOf(9, 8)
        val rejected = byteArrayOf(6, 5)

        assertArrayEquals(
            byteArrayOf(9, 8),
            salesExportContent(
                success(200, "text/csv; charset=utf-8", accepted),
                SalesOrderExportFormat.CSV
            )
        )
        assertNull(
            salesExportContent(
                success(200, "application/pdf", rejected),
                SalesOrderExportFormat.CSV
            )
        )
        assertArrayEquals(byteArrayOf(0, 0), rejected)
    }

    private fun success(status: Int, type: String, bytes: ByteArray) =
        ProtectedResult.Success(status, null, null, null, bytes, type)
}
