package com.nexa.mobile.operations.businessdocuments.infrastructure.pdf

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessDocumentPdfTempFilesTest {
    @Test
    fun cleanupRemovesPriorProcessArtifactsAndPreservesCurrentProcessFiles() {
        val directory = Files.createTempDirectory("business-document-pdf-test").toFile()
        try {
            val tempFiles = BusinessDocumentPdfTempFiles(directory)
            val currentProcess = tempFiles.create()
            val priorProcess = File(
                directory,
                "${BusinessDocumentPdfTempFiles.FILE_PREFIX}prior-process-token.pdf"
            ).apply { writeText("stale") }
            val legacyUnscoped = File(
                directory,
                "${BusinessDocumentPdfTempFiles.FILE_PREFIX}legacy.pdf"
            ).apply { writeText("stale") }
            val unrelated = File(directory, "unrelated.pdf").apply { writeText("keep") }

            BusinessDocumentPdfTempFiles(directory).cleanupPreviousProcessArtifacts()

            assertTrue(currentProcess.exists())
            assertFalse(priorProcess.exists())
            assertFalse(legacyUnscoped.exists())
            assertTrue(unrelated.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun createdArtifactCanBeRemoved() {
        val directory = Files.createTempDirectory("business-document-pdf-test").toFile()
        try {
            val tempFiles = BusinessDocumentPdfTempFiles(directory)
            val file = tempFiles.create()

            assertTrue(file.exists())
            tempFiles.delete(file)
            assertFalse(file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
