package com.nexa.mobile.operations

import java.net.URI
import java.util.regex.Pattern

internal class DriverMapTileRequestPolicy(template: String) {
    private val expectedHost: String?
    private val expectedPort: Int
    private val tilePath: Regex?

    init {
        val markers = mapOf(
            "{z}" to "NEXA_TILE_Z",
            "{x}" to "NEXA_TILE_X",
            "{y}" to "NEXA_TILE_Y"
        )
        val markerTemplate = markers.entries.fold(template) { value, (placeholder, marker) ->
            value.replace(placeholder, marker)
        }
        val parsed = runCatching { URI(markerTemplate) }.getOrNull()
        val path = parsed?.rawPath
        val valid = parsed?.scheme == "https" && parsed.host == "tile.openstreetmap.org" &&
            parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null &&
            markers.values.all { marker -> path?.contains(marker) == true }
        expectedHost = parsed?.host?.lowercase()?.takeIf { valid }
        expectedPort = parsed?.port?.takeIf { it != -1 } ?: 443
        tilePath = path?.takeIf { valid }?.let(::pathPattern)
    }

    fun allows(url: String): Boolean {
        val expectedHost = expectedHost ?: return false
        val tilePath = tilePath ?: return false
        val requested = runCatching { URI(url) }.getOrNull() ?: return false
        return requested.scheme == "https" && requested.host?.lowercase() == expectedHost &&
            (requested.port.takeIf { it != -1 } ?: 443) == expectedPort &&
            requested.rawUserInfo == null && requested.rawQuery == null &&
            requested.rawFragment == null && tilePath.matches(requested.rawPath.orEmpty())
    }

    private fun pathPattern(path: String): Regex {
        val marker = Regex("NEXA_TILE_[ZXY]")
        val matches = marker.findAll(path).toList()
        val pattern = buildString {
            append('^')
            var offset = 0
            matches.forEach { match ->
                append(Pattern.quote(path.substring(offset, match.range.first)))
                append("[0-9]+")
                offset = match.range.last + 1
            }
            append(Pattern.quote(path.substring(offset)))
            append('$')
        }
        return Regex(pattern)
    }
}
