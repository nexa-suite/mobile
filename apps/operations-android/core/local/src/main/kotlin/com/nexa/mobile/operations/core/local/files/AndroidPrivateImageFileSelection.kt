package com.nexa.mobile.operations.core.local.files

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Copies one user-selected image into scoped, private temporary storage. */
class AndroidPrivateImageFileSelection(context: Context) {
    private val appContext = context.applicationContext

    suspend fun prepare(uri: Uri, scopeKey: String): PrivateImageFileCandidate? =
        withContext(Dispatchers.IO) {
            if (uri.scheme != "content" || scopeKey.isBlank()) return@withContext null
            val mime =
                runCatching { appContext.contentResolver.getType(uri)?.lowercase() }.getOrNull()
                    ?: return@withContext null
            val extension = when (mime) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> return@withContext null
            }
            val scopedName = MessageDigest.getInstance("SHA-256").digest(scopeKey.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val directory = File(appContext.noBackupFilesDir, ROOT_DIRECTORY).resolve(scopedName)
            if (!directory.mkdirs() && !directory.isDirectory) return@withContext null
            val file = File(directory, "${UUID.randomUUID()}.$extension")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val header = ByteArray(12)
                var headerSize = 0
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: return@withContext null
                input.use { stream ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = stream.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            size += read
                            require(size <= MAX_BYTES) {
                                "Selected image exceeds the accepted local bound"
                            }
                            if (headerSize < header.size) {
                                val copied = minOf(read, header.size - headerSize)
                                buffer.copyInto(header, headerSize, 0, copied)
                                headerSize += copied
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                require(size > 0 && matchesImageHeader(mime, header, headerSize)) {
                    "Selected image format does not match its declared MIME"
                }
                PrivateImageFileCandidate(
                    file,
                    file.name,
                    mime,
                    size,
                    digest.digest().joinToString("") { "%02x".format(it) }
                )
            } catch (cancelled: CancellationException) {
                file.delete()
                throw cancelled
            } catch (_: Exception) {
                file.delete()
                null
            }
        }

    /** Raw selections are volatile and are never restored after process recreation. */
    fun discardAbandonedSelections() {
        if (!abandonedSelectionsCleared.compareAndSet(false, true)) return
        deleteSelectionRoot(File(appContext.noBackupFilesDir, ROOT_DIRECTORY))
        // Clean the prior app-owned location on upgrade so abandoned copies do not linger.
        deleteSelectionRoot(File(appContext.noBackupFilesDir, LEGACY_ROOT_DIRECTORY))
    }

    fun discard(candidate: PrivateImageFileCandidate) {
        val root = runCatching {
            File(appContext.noBackupFilesDir, ROOT_DIRECTORY).canonicalFile
        }.getOrNull() ?: return
        val selected = runCatching { candidate.file.canonicalFile }.getOrNull() ?: return
        if (selected.toPath().startsWith(root.toPath()) && selected != root) selected.delete()
    }

    private fun deleteSelectionRoot(root: File) {
        root.takeIf { it.isDirectory }?.listFiles()?.forEach { directory ->
            if (directory.isDirectory) {
                directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                directory.delete()
            }
        }
    }

    private fun matchesImageHeader(mime: String, header: ByteArray, size: Int): Boolean =
        when (mime) {
            "image/jpeg" -> size >= 3 && header[0] == 0xff.toByte() &&
                header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()

            "image/png" ->
                size >= 8 &&
                    header.take(8).toByteArray().contentEquals(
                        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                    )

            "image/webp" ->
                size >= 12 &&
                    String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 4, Charsets.US_ASCII) == "WEBP"

            else -> false
        }

    private companion object {
        const val MAX_BYTES = 10L * 1024L * 1024L
        const val ROOT_DIRECTORY = "private-image-selection"
        const val LEGACY_ROOT_DIRECTORY = "driver-proof-selection"
        val abandonedSelectionsCleared = AtomicBoolean(false)
    }
}
