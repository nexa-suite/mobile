package com.nexa.mobile.operations.core.local.scoped

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only accepted, caller-owned staging purposes. This store has no authority or replay behavior. */
enum class ScopedMetadataPurpose {
    DriverAttemptStart,
    FieldPurchaseRequest,
    FieldVisit,
    StockTransfer,
    DispatchAssignment,
    InboundDiscrepancyDraft,
    DriverDeliveryOutcome,
    StockTransferReceipt,
    OutgoingGoodsCheck,
    DriverArrival,
    FulfillmentDispatch,
    StockTransferReceiptObservation,
    DriverDeliveryArrival,
    CycleCountCorrection,
    DriverProofEvidence,
    LotSubstitutionRequest,
    DriverDeliveryIncident,
    DispatchPlanChange,
    InboundDiscrepancyEvidence,
    DriverHandoffToken,
    DispatchTemperatureEvidence,
    DispatchHandoffIdentity,
    DriverDeliveryInstructionAcknowledgement,
    DriverDeliveryOperationalException,
    CustomerDeliveryInstruction,
    DriverWorkdayCommand,
    DeliveryLoadCommand,
    DispatchDeliveryInstructionCommand,
    DriverExecutionTemperatureCommand,
    BusinessOperationalExceptionCommand
}
data class ScopedMetadataScope(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    init {
        require(
            listOf(userId, tenantId, workspaceId, membershipId).all {
                it.isNotBlank() &&
                    it.length <= 160
            }
        )
    }
    override fun toString(): String = "ScopedMetadataScope(REDACTED)"
}
sealed interface ScopedMetadataRead {
    class Value(val payload: String?) : ScopedMetadataRead {
        override fun toString(): String = "ScopedMetadataRead.Value(REDACTED)"
    }
    data object Unavailable : ScopedMetadataRead
}

/** Storage port for purpose-bound opaque metadata; it performs no network replay. */
interface ScopedMetadataStore {
    suspend fun load(scope: ScopedMetadataScope): ScopedMetadataRead
    suspend fun save(scope: ScopedMetadataScope, payload: String): Boolean
    suspend fun clear(scope: ScopedMetadataScope): Boolean
}

/** Atomic encrypted staging, bound to purpose and all four verified identity dimensions. */
class AndroidScopedMetadataStore(context: Context, private val purpose: ScopedMetadataPurpose) :
    ScopedMetadataStore {
    private val directory =
        File(context.applicationContext.noBackupFilesDir, "scoped-metadata/${purpose.name}")
    private val keyAlias = "com.nexa.mobile.operations.scoped-metadata.${purpose.name}.v1"
    private val lock = locks.getOrPut(directory.absolutePath) { Any() }

    override suspend fun load(scope: ScopedMetadataScope): ScopedMetadataRead =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    val file = record(scope)
                    val bytes = try {
                        file.openRead().use { input ->
                            val result = ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                check(result.size() + count <= MAX_ENCRYPTED_BYTES)
                                result.write(buffer, 0, count)
                            }
                            result.toByteArray()
                        }
                    } catch (missing: FileNotFoundException) {
                        if (artifacts(file.baseFile).any(File::exists) ||
                            (directory.exists() && !directory.canRead())
                        ) {
                            throw missing
                        }
                        return@synchronized ScopedMetadataRead.Value(null)
                    }
                    require(bytes.size in 33..MAX_ENCRYPTED_BYTES)
                    require(bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER))
                    val iv = bytes.copyOfRange(HEADER.size, HEADER.size + 12)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val key = keyForRead() ?: return@synchronized ScopedMetadataRead.Unavailable
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                    cipher.updateAAD(binding(scope))
                    val plaintext = cipher.doFinal(bytes.copyOfRange(HEADER.size + 12, bytes.size))
                    require(plaintext.size <= MAX_PLAINTEXT_BYTES)
                    ScopedMetadataRead.Value(plaintext.toString(Charsets.UTF_8))
                } catch (
                    cancelled: CancellationException
                ) {
                    throw cancelled
                } catch (_: Exception) {
                    ScopedMetadataRead.Unavailable
                }
            }
        }

    override suspend fun save(scope: ScopedMetadataScope, payload: String): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    val plaintext = payload.toByteArray(Charsets.UTF_8)
                    require(plaintext.size <= MAX_PLAINTEXT_BYTES)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, keyForWrite())
                    require(cipher.iv.size == 12)
                    cipher.updateAAD(binding(scope))
                    val encrypted = HEADER + cipher.iv + cipher.doFinal(plaintext)
                    check(directory.isDirectory || directory.mkdirs())
                    val file = record(scope)
                    val output = file.startWrite()
                    try {
                        output.write(encrypted)
                        file.finishWrite(output)
                    } catch (
                        failure: Exception
                    ) {
                        file.failWrite(output)
                        throw failure
                    }
                    true
                } catch (
                    cancelled: CancellationException
                ) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
        }

    override suspend fun clear(scope: ScopedMetadataScope): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try {
                val file = record(scope)
                file.delete()
                artifacts(file.baseFile).none(File::exists)
            } catch (
                cancelled: CancellationException
            ) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }
    private fun record(scope: ScopedMetadataScope): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(binding(scope))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 255) }
        return AtomicFile(File(directory, "$name.record"))
    }
    private fun binding(scope: ScopedMetadataScope): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { writer ->
            writer.write(HEADER)
            for (value in listOf(
                purpose.name,
                scope.userId,
                scope.tenantId,
                scope.workspaceId,
                scope.membershipId
            )) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                writer.writeInt(bytes.size)
                writer.write(bytes)
            }
        }
        return output.toByteArray()
    }
    private fun keyForRead(): SecretKey? = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }
        .getKey(keyAlias, null) as? SecretKey
    private fun keyForWrite(): SecretKey = keyForRead()
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(
                        KeyProperties.BLOCK_MODE_GCM
                    ).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build()
            )
            generateKey()
        }
    private fun artifacts(file: File) =
        listOf(file, File("${file.path}.bak"), File("${file.path}.new"))
    private companion object {
        val HEADER = "NXSM1".toByteArray(Charsets.US_ASCII)
        const val MAX_PLAINTEXT_BYTES = 64 * 1024
        const val MAX_ENCRYPTED_BYTES = MAX_PLAINTEXT_BYTES + 33
        val locks = ConcurrentHashMap<String, Any>()
    }
}
