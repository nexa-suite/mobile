package com.nexa.mobile.operations.inventoryavailability.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult

import java.io.File
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

private const val INBOUND_CASES_PATH = "/api/v1/inventory/inbound-discrepancy-cases"
private const val BUSINESS_EVIDENCE_PATH = "/api/v1/business-document-evidence"
private const val INBOUND_EVIDENCE_SUBJECT = "INBOUND_RECEIVING_DISCREPANCY"
private const val MAX_INBOUND_EVIDENCE_BYTES = 10L * 1024L * 1024L
private val inboundDiscrepancyJson = Json { ignoreUnknownKeys = true }
private val inboundDiscrepancyUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class InboundDiscrepancyCaseProjection(
    val id: String,
    val warehouseId: String,
    val expectedSkuId: String?,
    val observedSkuId: String,
    val expectedBatchReference: String?,
    val observedBatchReference: String?,
    val expectedQuantity: BigDecimal,
    val observedQuantity: BigDecimal,
    val unit: String,
    val reason: String,
    val observationNotes: String?,
    val status: String,
    val evidenceObjectId: String?,
    val version: Long,
    val recordedByMembershipId: String,
    val recordedAt: Instant,
    val submittedByMembershipId: String?,
    val submittedAt: Instant?
) {
    override fun toString(): String =
        "InboundDiscrepancyCaseProjection(status=$status, version=$version, facts=REDACTED)"
}

data class InboundDiscrepancyEvidenceProjection(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val lifecycleStatus: String,
    val declaredContentType: String,
    val checksumSha256: String?,
    val byteSize: Long
) {
    override fun toString(): String =
        "InboundDiscrepancyEvidenceProjection(status=$lifecycleStatus, bytes=$byteSize)"
}

sealed interface InboundDiscrepancyNetworkOutcome {
    data class CaseConfirmed(val value: InboundDiscrepancyCaseProjection) :
        InboundDiscrepancyNetworkOutcome
    data class EvidenceUploaded(val value: InboundDiscrepancyEvidenceProjection) :
        InboundDiscrepancyNetworkOutcome
    data class EvidenceStatus(val value: InboundDiscrepancyEvidenceProjection) :
        InboundDiscrepancyNetworkOutcome
    data class Rejected(val code: String?) : InboundDiscrepancyNetworkOutcome
    data object PreconditionFailed : InboundDiscrepancyNetworkOutcome
    data object Conflict : InboundDiscrepancyNetworkOutcome
    data object UnknownOutcome : InboundDiscrepancyNetworkOutcome
    data object NetworkUnavailable : InboundDiscrepancyNetworkOutcome
    data object ServiceUnavailable : InboundDiscrepancyNetworkOutcome
    data object PermissionDenied : InboundDiscrepancyNetworkOutcome
    data object ContextInvalidated : InboundDiscrepancyNetworkOutcome
    data object SessionInvalidated : InboundDiscrepancyNetworkOutcome
}

/** Protected transport for immutable receiving observations, exact-subject photo evidence and review submission. */
class NexaInboundDiscrepancyGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun createCase(
        idempotencyKey: String,
        frozenBody: String
    ): InboundDiscrepancyNetworkOutcome {
        if (!validKey(idempotencyKey) || !createBodyIsValid(frozenBody)) {
            return InboundDiscrepancyNetworkOutcome.Rejected("INVALID_REQUEST")
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    ProtectedMethod.POST,
                    INBOUND_CASES_PATH,
                    payload = frozenBody,
                    idempotencyKey = idempotencyKey
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toInboundDiscrepancyOutcome()

            is ProtectedResult.Success -> {
                if (result.status !in
                    setOf(200, 201)
                ) {
                    return InboundDiscrepancyNetworkOutcome.UnknownOutcome
                }
                val projection = result.body.toCaseProjection()
                    ?: return InboundDiscrepancyNetworkOutcome.UnknownOutcome
                if (!projection.matchesCreateBody(frozenBody) ||
                    result.etag.toVersion() != projection.version
                ) {
                    InboundDiscrepancyNetworkOutcome.UnknownOutcome
                } else {
                    InboundDiscrepancyNetworkOutcome.CaseConfirmed(projection)
                }
            }
        }
    }

    suspend fun uploadEvidence(
        caseId: String,
        idempotencyKey: String,
        file: File,
        originalFilename: String,
        declaredContentType: String,
        byteSize: Long,
        checksumSha256: String
    ): InboundDiscrepancyNetworkOutcome {
        if (!inboundDiscrepancyUuid.matches(caseId) || !validKey(idempotencyKey) ||
            !file.isFile || file.length() != byteSize ||
            byteSize !in 1..MAX_INBOUND_EVIDENCE_BYTES ||
            declaredContentType !in setOf("image/jpeg", "image/png", "image/webp") ||
            !checksumSha256.matches(Regex("[0-9a-f]{64}")) || originalFilename.isBlank() ||
            originalFilename.length > 255 ||
            originalFilename.any { it == '\r' || it == '\n' || it == '/' || it == '\\' }
        ) {
            return InboundDiscrepancyNetworkOutcome.Rejected("INVALID_EVIDENCE")
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("subjectType", INBOUND_EVIDENCE_SUBJECT)
            .addFormDataPart("subjectId", caseId)
            .addFormDataPart(
                "file",
                originalFilename,
                file.asRequestBody(declaredContentType.toMediaType())
            )
            .build()
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = BUSINESS_EVIDENCE_PATH,
                    idempotencyKey = idempotencyKey,
                    requestBody = body
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toInboundDiscrepancyOutcome()

            is ProtectedResult.Success -> {
                if (result.status !in
                    setOf(200, 201)
                ) {
                    return InboundDiscrepancyNetworkOutcome.UnknownOutcome
                }
                val evidence = result.body.toEvidenceProjection()
                    ?: return InboundDiscrepancyNetworkOutcome.UnknownOutcome
                if (evidence.subjectType != INBOUND_EVIDENCE_SUBJECT ||
                    evidence.subjectId != caseId ||
                    evidence.declaredContentType != declaredContentType ||
                    evidence.byteSize != byteSize
                ) {
                    return InboundDiscrepancyNetworkOutcome.UnknownOutcome
                }
                if (evidence.checksumSha256 != null && evidence.checksumSha256 != checksumSha256) {
                    InboundDiscrepancyNetworkOutcome.Rejected("IDEMPOTENCY_PAYLOAD_CONFLICT")
                } else {
                    InboundDiscrepancyNetworkOutcome.EvidenceUploaded(evidence)
                }
            }
        }
    }

    suspend fun evidenceStatus(
        evidenceId: String,
        caseId: String
    ): InboundDiscrepancyNetworkOutcome {
        if (!inboundDiscrepancyUuid.matches(evidenceId) ||
            !inboundDiscrepancyUuid.matches(caseId)
        ) {
            return InboundDiscrepancyNetworkOutcome.Rejected("INVALID_REQUEST")
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, "$BUSINESS_EVIDENCE_PATH/$evidenceId")
            )
        ) {
            is ProtectedResult.Failure -> result.error.toInboundDiscrepancyOutcome()

            is ProtectedResult.Success -> {
                val evidence = result.body.toEvidenceProjection()
                    ?: return InboundDiscrepancyNetworkOutcome.ServiceUnavailable
                if (evidence.id != evidenceId || evidence.subjectType != INBOUND_EVIDENCE_SUBJECT ||
                    evidence.subjectId != caseId
                ) {
                    InboundDiscrepancyNetworkOutcome.ServiceUnavailable
                } else {
                    InboundDiscrepancyNetworkOutcome.EvidenceStatus(evidence)
                }
            }
        }
    }

    suspend fun submitForReview(
        caseId: String,
        evidenceId: String,
        expectedVersion: Long,
        idempotencyKey: String,
        frozenBody: String
    ): InboundDiscrepancyNetworkOutcome {
        if (!inboundDiscrepancyUuid.matches(
                caseId
            ) || !inboundDiscrepancyUuid.matches(evidenceId) ||
            expectedVersion < 0 || !validKey(idempotencyKey) ||
            !submitBodyMatches(frozenBody, evidenceId)
        ) {
            return InboundDiscrepancyNetworkOutcome.Rejected("INVALID_REQUEST")
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$INBOUND_CASES_PATH/$caseId/submissions",
                    payload = frozenBody,
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"$expectedVersion\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toInboundDiscrepancyOutcome()

            is ProtectedResult.Success -> {
                if (result.status !in
                    setOf(200, 201)
                ) {
                    return InboundDiscrepancyNetworkOutcome.UnknownOutcome
                }
                val projection = result.body.toCaseProjection()
                    ?: return InboundDiscrepancyNetworkOutcome.UnknownOutcome
                if (projection.id != caseId || projection.status != "READY_FOR_REVIEW" ||
                    projection.evidenceObjectId != evidenceId ||
                    projection.version <= expectedVersion ||
                    result.etag.toVersion() != projection.version
                ) {
                    InboundDiscrepancyNetworkOutcome.UnknownOutcome
                } else {
                    InboundDiscrepancyNetworkOutcome.CaseConfirmed(projection)
                }
            }
        }
    }

    private fun String?.toCaseProjection(): InboundDiscrepancyCaseProjection? = try {
        val wire =
            this?.let { inboundDiscrepancyJson.decodeFromString<CaseResponseWire>(it) }
                ?: return null
        val id = wire.id ?: return null
        val warehouseId = wire.warehouseId ?: return null
        val observedSkuId = wire.observedSkuId ?: return null
        val expectedQuantity = wire.expectedQuantity?.content?.toBigDecimalOrNull() ?: return null
        val observedQuantity = wire.observedQuantity?.content?.toBigDecimalOrNull() ?: return null
        val recordedBy = wire.recordedByMembershipId ?: return null
        val recordedAt = wire.recordedAt?.let(Instant::parse) ?: return null
        InboundDiscrepancyCaseProjection(
            id, warehouseId, wire.expectedSkuId, observedSkuId, wire.expectedBatchReference,
            wire.observedBatchReference, expectedQuantity, observedQuantity,
            wire.unit ?: return null,
            wire.reason ?: return null, wire.observationNotes, wire.status ?: return null,
            wire.evidenceObjectId, wire.version ?: return null, recordedBy, recordedAt,
            wire.submittedByMembershipId, wire.submittedAt?.let(Instant::parse)
        ).takeIf {
            inboundDiscrepancyUuid.matches(
                it.id
            ) && inboundDiscrepancyUuid.matches(it.warehouseId) &&
                inboundDiscrepancyUuid.matches(it.observedSkuId) &&
                (it.expectedSkuId == null || inboundDiscrepancyUuid.matches(it.expectedSkuId)) &&
                it.version >= 0 && it.reason.isNotBlank() && it.unit.isNotBlank() &&
                inboundDiscrepancyUuid.matches(it.recordedByMembershipId)
        }
    } catch (_: Exception) {
        null
    }

    private fun String?.toEvidenceProjection(): InboundDiscrepancyEvidenceProjection? = try {
        val wire =
            this?.let { inboundDiscrepancyJson.decodeFromString<EvidenceResponseWire>(it) }
                ?: return null
        InboundDiscrepancyEvidenceProjection(
            id = wire.id ?: return null,
            subjectType = wire.subjectType ?: return null,
            subjectId = wire.subjectId ?: return null,
            lifecycleStatus = wire.lifecycleStatus ?: return null,
            declaredContentType = wire.declaredContentType ?: return null,
            checksumSha256 = wire.checksumSha256,
            byteSize = wire.byteSize ?: return null
        ).takeIf {
            inboundDiscrepancyUuid.matches(it.id) && inboundDiscrepancyUuid.matches(it.subjectId) &&
                it.lifecycleStatus.isNotBlank() && it.byteSize in 1..MAX_INBOUND_EVIDENCE_BYTES
        }
    } catch (_: Exception) {
        null
    }

    private fun InboundDiscrepancyCaseProjection.matchesCreateBody(body: String): Boolean = try {
        val wire = inboundDiscrepancyJson.decodeFromString<CreateCaseWire>(body)
        warehouseId == wire.warehouseId && expectedSkuId == wire.expectedSkuId &&
            observedSkuId == wire.observedSkuId &&
            expectedBatchReference == wire.expectedBatchReference &&
            observedBatchReference == wire.observedBatchReference &&
            expectedQuantity.compareTo(
                wire.expectedQuantity?.content?.toBigDecimalOrNull() ?: return false
            ) ==
            0 &&
            observedQuantity.compareTo(
                wire.observedQuantity?.content?.toBigDecimalOrNull() ?: return false
            ) ==
            0 &&
            unit == wire.unit && reason == wire.reason &&
            observationNotes == wire.observationNotes &&
            status == "PENDING_EVIDENCE"
    } catch (_: Exception) {
        false
    }

    private fun createBodyIsValid(body: String): Boolean = try {
        val wire = inboundDiscrepancyJson.decodeFromString<CreateCaseWire>(body)
        wire.warehouseId?.let(inboundDiscrepancyUuid::matches) == true &&
            wire.observedSkuId?.let(inboundDiscrepancyUuid::matches) == true &&
            (wire.expectedSkuId == null || inboundDiscrepancyUuid.matches(wire.expectedSkuId)) &&
            wire.expectedQuantity?.content?.toBigDecimalOrNull()?.signum()?.let {
                it >= 0
            } == true &&
            wire.observedQuantity?.content?.toBigDecimalOrNull()?.signum()?.let {
                it >= 0
            } == true &&
            !wire.unit.isNullOrBlank() && !wire.reason.isNullOrBlank()
    } catch (_: Exception) {
        false
    }

    private fun submitBodyMatches(body: String, evidenceId: String): Boolean = try {
        inboundDiscrepancyJson.decodeFromString<SubmitCaseWire>(body).evidenceObjectId == evidenceId
    } catch (_: Exception) {
        false
    }

    private fun validKey(value: String): Boolean = value.isNotBlank() && value.length <= 160
}

@Serializable
private data class CaseResponseWire(
    val id: String? = null,
    val warehouseId: String? = null,
    val expectedSkuId: String? = null,
    val observedSkuId: String? = null,
    val expectedBatchReference: String? = null,
    val observedBatchReference: String? = null,
    val expectedQuantity: JsonPrimitive? = null,
    val observedQuantity: JsonPrimitive? = null,
    val unit: String? = null,
    val reason: String? = null,
    val observationNotes: String? = null,
    val status: String? = null,
    val evidenceObjectId: String? = null,
    val version: Long? = null,
    val recordedByMembershipId: String? = null,
    val recordedAt: String? = null,
    val submittedByMembershipId: String? = null,
    val submittedAt: String? = null
)

@Serializable
private data class EvidenceResponseWire(
    val id: String? = null,
    val subjectType: String? = null,
    val subjectId: String? = null,
    val lifecycleStatus: String? = null,
    val declaredContentType: String? = null,
    val checksumSha256: String? = null,
    val byteSize: Long? = null
)

@Serializable
private data class CreateCaseWire(
    val warehouseId: String? = null,
    val expectedSkuId: String? = null,
    val observedSkuId: String? = null,
    val expectedBatchReference: String? = null,
    val observedBatchReference: String? = null,
    val expectedQuantity: JsonPrimitive? = null,
    val observedQuantity: JsonPrimitive? = null,
    val unit: String? = null,
    val reason: String? = null,
    val observationNotes: String? = null
)

@Serializable
private data class SubmitCaseWire(val evidenceObjectId: String? = null)

private fun String?.toVersion(): Long? = this?.trim()?.takeIf {
    it.startsWith('"') &&
        it.endsWith('"')
}
    ?.substring(1, this.length - 1)?.toLongOrNull()?.takeIf { it >= 0 }

private fun ClientFailure.toInboundDiscrepancyOutcome(): InboundDiscrepancyNetworkOutcome =
    when (kind) {
        FailureKind.ValidationFailure -> InboundDiscrepancyNetworkOutcome.Rejected(problemCode)

        FailureKind.AuthenticationRequired -> InboundDiscrepancyNetworkOutcome.SessionInvalidated

        FailureKind.AuthorizationFailure -> InboundDiscrepancyNetworkOutcome.PermissionDenied

        FailureKind.ResourceUnavailable -> InboundDiscrepancyNetworkOutcome.Rejected(
            problemCode ?: "NOT_FOUND"
        )

        FailureKind.BusinessConflict -> InboundDiscrepancyNetworkOutcome.Conflict

        FailureKind.StaleState -> InboundDiscrepancyNetworkOutcome.PreconditionFailed

        FailureKind.PreconditionRequired -> InboundDiscrepancyNetworkOutcome.Rejected(
            problemCode ?: "PRECONDITION_REQUIRED"
        )

        FailureKind.NetworkUnavailable, FailureKind.Timeout ->
            InboundDiscrepancyNetworkOutcome.NetworkUnavailable

        FailureKind.UnknownOutcome -> InboundDiscrepancyNetworkOutcome.UnknownOutcome

        FailureKind.RetryableServerFailure, FailureKind.Throttled, FailureKind.ProtocolFailure ->
            InboundDiscrepancyNetworkOutcome.ServiceUnavailable
    }
