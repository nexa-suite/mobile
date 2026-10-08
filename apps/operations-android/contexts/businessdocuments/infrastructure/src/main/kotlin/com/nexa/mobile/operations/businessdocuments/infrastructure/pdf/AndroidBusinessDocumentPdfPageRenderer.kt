package com.nexa.mobile.operations.businessdocuments.infrastructure.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.nexa.mobile.operations.businessdocuments.application.commercial.BusinessDocumentPdfPageRenderer
import com.nexa.mobile.operations.businessdocuments.application.commercial.RenderedBusinessDocumentPage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AndroidBusinessDocumentPdfPageRenderer @Inject constructor(
    @ApplicationContext context: Context
) : BusinessDocumentPdfPageRenderer {
    private val temporaryFiles = BusinessDocumentPdfTempFiles(
        File(context.cacheDir, TEMP_DIRECTORY_NAME)
    )

    init {
        cleanupLegacyTemporaryFiles(context.noBackupFilesDir)
    }

    override suspend fun renderPage(
        pdfBytes: ByteArray,
        pageIndex: Int
    ): RenderedBusinessDocumentPage? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (pdfBytes.isEmpty() || pageIndex < 0) return@withContext null

        var temporaryFile: File? = null
        var descriptorToClose: ParcelFileDescriptor? = null
        try {
            val file = temporaryFiles.create()
            temporaryFile = file
            file.outputStream().use { output -> output.write(pdfBytes) }
            currentCoroutineContext().ensureActive()

            val descriptor = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            descriptorToClose = descriptor

            // This private cacheDir file is transient parser input, not a durable document cache.
            // Unlink it as soon as the open fd can serve the renderer; retry cleanup in finally.
            if (file.exists() && !file.delete()) {
                throw IOException("Unable to remove temporary PDF input")
            }
            currentCoroutineContext().ensureActive()

            val renderer = PdfRenderer(descriptor)
            descriptorToClose = null
            renderer.use { pdfRenderer ->
                val totalPages = pdfRenderer.pageCount
                if (pageIndex !in 0 until totalPages) return@withContext null

                pdfRenderer.openPage(pageIndex).use { page ->
                    currentCoroutineContext().ensureActive()
                    val dimensions = renderDimensions(page.width, page.height)
                        ?: return@withContext null
                    val (width, height) = dimensions
                    val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(
                            bitmap,
                            null,
                            null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        currentCoroutineContext().ensureActive()
                        val pixels = IntArray(width * height)
                        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                        currentCoroutineContext().ensureActive()
                        RenderedBusinessDocumentPage(width, height, pixels, totalPages)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            try {
                descriptorToClose?.close()
            } catch (_: Exception) {
                // Preserve cancellation and still attempt to remove the staged input below.
            }
            try {
                temporaryFile?.let(temporaryFiles::delete)
            } catch (_: Exception) {
                // The next process prunes artifacts that could not be removed here.
            }
        }
    }

    private fun renderDimensions(pageWidth: Int, pageHeight: Int): Pair<Int, Int>? {
        if (pageWidth <= 0 || pageHeight <= 0) return null
        val pixelLimitScale = sqrt(
            RenderedBusinessDocumentPage.MAX_PIXEL_COUNT.toDouble() /
                (pageWidth.toDouble() * pageHeight)
        )
        val scale = minOf(
            2.0,
            RenderedBusinessDocumentPage.MAX_WIDTH.toDouble() / pageWidth,
            RenderedBusinessDocumentPage.MAX_HEIGHT.toDouble() / pageHeight,
            pixelLimitScale
        )
        val width = (pageWidth * scale).toInt().coerceAtLeast(1)
        val height = (pageHeight * scale).toInt().coerceAtLeast(1)
        if (width > RenderedBusinessDocumentPage.MAX_WIDTH ||
            height > RenderedBusinessDocumentPage.MAX_HEIGHT ||
            width.toLong() * height > RenderedBusinessDocumentPage.MAX_PIXEL_COUNT
        ) {
            return null
        }
        return width to height
    }

    private fun cleanupLegacyTemporaryFiles(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(LEGACY_FILE_PREFIX) &&
                file.name.endsWith(LEGACY_FILE_SUFFIX)
            ) {
                file.delete()
            }
        }
    }

    private companion object {
        const val TEMP_DIRECTORY_NAME = "business-document-pdf-render"
        const val LEGACY_FILE_PREFIX = "protected-document-"
        const val LEGACY_FILE_SUFFIX = ".pdf"
    }
}
