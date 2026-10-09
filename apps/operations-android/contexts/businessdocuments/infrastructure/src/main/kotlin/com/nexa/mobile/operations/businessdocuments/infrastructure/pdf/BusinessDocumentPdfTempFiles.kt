package com.nexa.mobile.operations.businessdocuments.infrastructure.pdf

import java.io.File
import java.util.UUID

internal class BusinessDocumentPdfTempFiles(private val directory: File) {
    init {
        cleanupPreviousProcessArtifacts()
    }

    fun create(): File {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("Private PDF temporary directory unavailable")
        }
        cleanupPreviousProcessArtifacts()
        return File.createTempFile(CURRENT_PROCESS_FILE_PREFIX, FILE_SUFFIX, directory)
    }

    fun cleanupPreviousProcessArtifacts() {
        directory.listFiles()?.forEach { file ->
            if (file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX) &&
                file.isFile && !file.name.startsWith(CURRENT_PROCESS_FILE_PREFIX)
            ) {
                file.delete()
            }
        }
    }

    fun delete(file: File) {
        if (file.exists()) file.delete()
    }

    companion object {
        internal const val FILE_PREFIX = "business-document-render-"
        internal const val FILE_SUFFIX = ".pdf"
        private val PROCESS_TOKEN = UUID.randomUUID().toString()
        private val CURRENT_PROCESS_FILE_PREFIX = "$FILE_PREFIX$PROCESS_TOKEN-"
    }
}
