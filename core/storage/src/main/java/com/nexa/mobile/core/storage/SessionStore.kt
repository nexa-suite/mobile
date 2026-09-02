package com.nexa.mobile.core.storage

data class SessionMaterial(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAtEpochSeconds: Long,
    val surface: String,
) {
    init {
        require(accessToken.isNotBlank()) { "Access token is required" }
        require(refreshToken.isNotBlank()) { "Refresh token is required" }
        require(accessTokenExpiresAtEpochSeconds >= 0) { "Access token expiry is invalid" }
        require(surface == "PLATFORM" || surface == "PORTAL") {
            "Session surface is not an observed API surface"
        }
    }
}

interface SessionStore {
    suspend fun read(): SessionMaterial?

    suspend fun write(material: SessionMaterial)

    suspend fun clear()
}
