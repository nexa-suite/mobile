package com.nexa.mobile.operations.feature.delivery

import java.io.File

/** Bounded, private picker copy. It is input data only; the server owns evidence availability. */
data class DriverProofFileCandidate(
    val file: File,
    val originalFilename: String,
    val declaredContentType: String,
    val byteSize: Long,
    val checksumSha256: String
) {
    init {
        require(file.isFile && file.length() == byteSize)
        require(originalFilename.isNotBlank() && originalFilename.length <= 255)
        require(declaredContentType in ALLOWED_CONTENT_TYPES)
        require(byteSize in 1..MAX_BYTES)
        require(checksumSha256.matches(Regex("[0-9a-f]{64}")))
    }

    override fun toString(): String =
        "DriverProofFileCandidate(contentType=$declaredContentType, bytes=$byteSize, checksum=REDACTED)"

    private companion object {
        const val MAX_BYTES = 10L * 1024L * 1024L
        val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
