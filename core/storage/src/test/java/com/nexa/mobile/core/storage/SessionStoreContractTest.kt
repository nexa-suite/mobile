package com.nexa.mobile.core.storage

import java.nio.charset.StandardCharsets
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionStoreContractTest {
    private val material = SessionMaterial(
        accessToken = "access-token-value",
        refreshToken = "refresh-token-value",
        accessTokenExpiresAtEpochSeconds = 1_800_000_000,
        surface = "PLATFORM",
    )

    @Test
    fun encryptedStoreRoundTripsWithoutPlaintextPayload() {
        val cipher = SessionCipher(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))

        val encrypted = cipher.encrypt(material)
        val serialized = encrypted.toString(StandardCharsets.UTF_8)

        assertFalse(serialized.contains(material.accessToken))
        assertFalse(serialized.contains(material.refreshToken))
        assertEquals(material, cipher.decrypt(encrypted))
    }

    @Test
    fun fakeStoreRoundTripsAndClears() = runBlocking {
        val store = FakeSessionStore()

        store.write(material)
        assertEquals(material, store.read())

        store.clear()
        assertEquals(null, store.read())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownApiSurfaceIsRejected() {
        material.copy(surface = "OPERATIONS")
    }

    private class FakeSessionStore : SessionStore {
        private var value: SessionMaterial? = null

        override suspend fun read(): SessionMaterial? = value

        override suspend fun write(material: SessionMaterial) {
            value = material
        }

        override suspend fun clear() {
            value = null
        }
    }
}
