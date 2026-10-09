package com.nexa.mobile.operations.businessdocuments.infrastructure.pdf

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexa.mobile.operations.businessdocuments.application.commercial.RenderedBusinessDocumentPage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidBusinessDocumentPdfPageRendererTest {
    @Test
    fun rendersSyntheticPdfToBoundedPixelsAndLeavesNoStagedFile() {
        val fixture = rendererFixture()
        try {
            val page = runBlocking {
                fixture.renderer.renderPage(syntheticPdfBytes(), pageIndex = 0)
            }

            assertNotNull(page)
            val renderedPage = requireNotNull(page)
            assertEquals(1, renderedPage.totalPages)
            assertTrue(renderedPage.width in 1..RenderedBusinessDocumentPage.MAX_WIDTH)
            assertTrue(renderedPage.height in 1..RenderedBusinessDocumentPage.MAX_HEIGHT)
            assertTrue(
                renderedPage.width.toLong() * renderedPage.height <=
                    RenderedBusinessDocumentPage.MAX_PIXEL_COUNT.toLong()
            )
            assertEquals(renderedPage.width * renderedPage.height, renderedPage.argbPixels.size)
            assertTrue(renderedPage.argbPixels.any { it != Color.WHITE })
            assertNoStagedFiles(fixture.cacheDirectory)
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun invalidPdfReturnsUnavailableAndLeavesNoStagedFile() {
        val fixture = rendererFixture()
        try {
            val page = runBlocking {
                fixture.renderer.renderPage(byteArrayOf(0, 1, 2, 3, 4), pageIndex = 0)
            }

            assertNull(page)
            assertNoStagedFiles(fixture.cacheDirectory)
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun cancelledCoroutinePropagatesCancellationBeforeCreatingFile() {
        val fixture = rendererFixture()
        try {
            val cancelledJob = Job().apply {
                cancel(CancellationException("cancel before PDF rendering"))
            }
            val completed = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val operation: suspend () -> RenderedBusinessDocumentPage? = {
                fixture.renderer.renderPage(syntheticPdfBytes(), pageIndex = 0)
            }

            operation.startCoroutine(
                object : Continuation<RenderedBusinessDocumentPage?> {
                    override val context = cancelledJob + Dispatchers.Unconfined

                    override fun resumeWith(result: Result<RenderedBusinessDocumentPage?>) {
                        failure.set(result.exceptionOrNull())
                        completed.countDown()
                    }
                }
            )

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(failure.get() is CancellationException)
            assertFalse(File(fixture.cacheDirectory, RENDER_DIRECTORY_NAME).exists())
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun rendererConstructionRemovesOnlyLegacyPdfTemporaryFiles() {
        val fixture = rendererFixture(createRenderer = false)
        try {
            val legacy = File(fixture.noBackupDirectory, "${LEGACY_PREFIX}old.pdf")
                .apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val unrelated = File(fixture.noBackupDirectory, "other-record.bin")
                .apply { writeBytes(byteArrayOf(4, 5, 6)) }

            fixture.createRenderer()

            assertFalse(legacy.exists())
            assertTrue(unrelated.exists())
        } finally {
            fixture.delete()
        }
    }

    private fun rendererFixture(createRenderer: Boolean = true): RendererFixture {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val cacheDirectory = File(appContext.cacheDir, "pdf-render-test-$token")
            .apply { mkdirs() }
        val noBackupDirectory = File(appContext.noBackupFilesDir, "pdf-render-test-$token")
            .apply { mkdirs() }
        val context = object : ContextWrapper(appContext) {
            override fun getCacheDir(): File = cacheDirectory
            override fun getNoBackupFilesDir(): File = noBackupDirectory
        }
        val fixture = RendererFixture(context, cacheDirectory, noBackupDirectory)
        if (createRenderer) fixture.createRenderer()
        return fixture
    }

    private fun syntheticPdfBytes(): ByteArray {
        val document = PdfDocument()
        return try {
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(200, 300, 1).create()
            )
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText(
                "Renderer fixture",
                12f,
                48f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 20f
                }
            )
            document.finishPage(page)
            ByteArrayOutputStream().use { output ->
                document.writeTo(output)
                output.toByteArray()
            }
        } finally {
            document.close()
        }
    }

    private fun assertNoStagedFiles(cacheDirectory: File) {
        val rendererDirectory = File(cacheDirectory, RENDER_DIRECTORY_NAME)
        assertTrue(rendererDirectory.isDirectory)
        assertTrue(rendererDirectory.listFiles().orEmpty().isEmpty())
    }

    private class RendererFixture(
        private val context: Context,
        val cacheDirectory: File,
        val noBackupDirectory: File
    ) {
        lateinit var renderer: AndroidBusinessDocumentPdfPageRenderer
            private set

        fun createRenderer() {
            renderer = AndroidBusinessDocumentPdfPageRenderer(context)
        }

        fun delete() {
            cacheDirectory.deleteRecursively()
            noBackupDirectory.deleteRecursively()
        }
    }

    private companion object {
        const val LEGACY_PREFIX = "protected-document-"
        const val RENDER_DIRECTORY_NAME = "business-document-pdf-render"
    }
}
