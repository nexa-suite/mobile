package com.nexa.mobile.operations.core.designsystem

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes

/**
 * Loads bundled catalog assets at the size used by the UI.
 *
 * The source files remain the canonical PNG/JPEG assets. This loader only samples their decoded
 * bitmaps and keeps a small byte-bounded cache so a long catalog list cannot retain full-size
 * source images in memory.
 */
internal object CatalogImageBitmapLoader {
    private const val CACHE_SIZE_KIB = 8 * 1024

    private data class CacheKey(
        @DrawableRes val resourceId: Int,
        val targetWidthPx: Int,
        val targetHeightPx: Int
    )

    private val cache = object : LruCache<CacheKey, Bitmap>(CACHE_SIZE_KIB) {
        override fun sizeOf(key: CacheKey, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    fun load(
        resources: Resources,
        @DrawableRes resourceId: Int,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? {
        val safeWidth = targetWidthPx.coerceAtLeast(1)
        val safeHeight = targetHeightPx.coerceAtLeast(1)
        val key = CacheKey(resourceId, safeWidth, safeHeight)
        cache.get(key)?.let { return it }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, resourceId, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decoded = BitmapFactory.decodeResource(
            resources,
            resourceId,
            BitmapFactory.Options().apply {
                inSampleSize = CatalogImageDecodePolicy.calculateInSampleSize(
                    sourceWidthPx = bounds.outWidth,
                    sourceHeightPx = bounds.outHeight,
                    targetWidthPx = safeWidth,
                    targetHeightPx = safeHeight
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
        ) ?: return null

        cache.put(key, decoded)
        return decoded
    }
}

/** Pure sizing policy kept separate so its memory bounds can be tested without Android resources. */
internal object CatalogImageDecodePolicy {
    const val MAX_CACHE_BYTES = 8 * 1024 * 1024

    fun calculateInSampleSize(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Int {
        if (sourceWidthPx <= 0 || sourceHeightPx <= 0) return 1
        val targetWidth = targetWidthPx.coerceAtLeast(1)
        val targetHeight = targetHeightPx.coerceAtLeast(1)
        var sample = 1
        while (
            sourceWidthPx / (sample * 2) >= targetWidth &&
            sourceHeightPx / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        return sample
    }

    fun estimatedArgb8888Bytes(widthPx: Int, heightPx: Int): Long =
        widthPx.toLong().coerceAtLeast(0) * heightPx.toLong().coerceAtLeast(0) * 4
}
