package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

private const val DRIVER_INCIDENT_BASE = "/api/v1/driver/deliveries"
private const val INCIDENT_EVIDENCE_BASE = "/api/v1/business-document-evidence"
private val incidentJson = Json { ignoreUnknownKeys = true }
private val incidentUuid = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class DriverIncidentProjection(
    val id: String,
    val deliveryId: String,
    val attemptId: String,
    val reason: String,
    val description: String,
    val place: String,
    val recordedByMembershipId: String,
    val recordedAt: String,
    val evidenceObjectIds: List<String>,
    val deliveryVersion: Long,
    val replayed: Boolean,
    val type: String?,
    val severity: String?,
    val operationalExceptionId: String?
)

data class DriverIncidentEvidenceProjection(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val lifecycleStatus: String,
    val declaredContentType: String,
    val checksumSha256: String?,
    val byteSize: Long
)

sealed interface DriverIncidentNetworkOutcome {
    data class Recorded(val incident: DriverIncidentProjection) : DriverIncidentNetworkOutcome
    data class EvidenceUploaded(val evidence: DriverIncidentEvidenceProjection) :
        DriverIncidentNetworkOutcome
    data class EvidenceStatus(val evidence: DriverIncidentEvidenceProjection) :
        DriverIncidentNetworkOutcome
    data class EvidenceAttached(val incident: DriverIncidentProjection) :
        DriverIncidentNetworkOutcome
    data class Rejected(val code: String?) : DriverIncidentNetworkOutcome
    data object NotFound : DriverIncidentNetworkOutcome
    data object StaleVersion : DriverIncidentNetworkOutcome
    data object UnknownOutcome : DriverIncidentNetworkOutcome
    data object Unavailable : DriverIncidentNetworkOutcome
    data object PermissionDenied : DriverIncidentNetworkOutcome
    data object ContextInvalidated : DriverIncidentNetworkOutcome
    data object SessionInvalidated : DriverIncidentNetworkOutcome
}

/** Transport for the current-driver append-only incident command. */
class NexaDriverIncidentGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun record(command: DriverIncidentWireCommand): DriverIncidentNetworkOutcome {
        if (!incidentUuid.matches(command.deliveryId) || !incidentUuid.matches(command.attemptId) ||
            command.expectedVersion < 0 || command.idempotencyKey.isBlank() ||
            command.idempotencyKey.length > 160 ||
            (command.type != null && command.type !in INCIDENT_TYPES) || !bodyMatches(command)
        ) {
            return DriverIncidentNetworkOutcome.Unavailable
        }

        val path =
            "$DRIVER_INCIDENT_BASE/${command.deliveryId}/attempts/${command.attemptId}/incidents"
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = path,
                    payload = command.frozenBody,
                    idempotencyKey = command.idempotencyKey,
                    ifMatch = "\"${command.expectedVersion}\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toIncidentOutcome()

            is ProtectedResult.Success -> {
                val incident =
                    result.body.toIncidentProjection()
                        ?: return DriverIncidentNetworkOutcome.UnknownOutcome
                val etagVersion = result.etag.toVersion()
                if (incident.deliveryId != command.deliveryId ||
                    incident.attemptId != command.attemptId ||
                    incident.reason != command.reason ||
                    incident.description != command.description ||
                    incident.place != command.place || incident.deliveryVersion != etagVersion ||
                    incident.deliveryVersion < command.expectedVersion ||
                    !classificationMatches(command, incident) ||
                    incident.replayed != (result.status == 200) || result.status !in setOf(200, 201)
                ) {
                    DriverIncidentNetworkOutcome.UnknownOutcome
                } else {
                    DriverIncidentNetworkOutcome.Recorded(incident)
                }
            }
        }
    }

    suspend fun uploadEvidence(
        incidentId: String,
        idempotencyKey: String,
        file: File,
        originalFilename: String,
        declaredContentType: String,
        byteSize: Long,
        checksumSha256: String
    ): DriverIncidentNetworkOutcome {
        if (!incidentUuid.matches(incidentId) || idempotencyKey.isBlank() ||
            idempotencyKey.length > 160 ||
            !file.isFile || file.length() != byteSize || byteSize !in 1..MAX_EVIDENCE_BYTES ||
            declaredContentType !in ALLOWED_EVIDENCE_TYPES ||
            !checksumSha256.matches(Regex("[0-9a-f]{64}")) ||
            originalFilename.isBlank() || originalFilename.length > 255 ||
            originalFilename.any { it == '\r' || it == '\n' || it == '/' || it == '\\' }
        ) {
            return DriverIncidentNetworkOutcome.Unavailable
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("subjectType", "DELIVERY_INCIDENT")
            .addFormDataPart("subjectId", incidentId)
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
                    path = INCIDENT_EVIDENCE_BASE,
                    idempotencyKey = idempotencyKey,
                    requestBody = body
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toIncidentOutcome()

            is ProtectedResult.Success -> {
                if (result.status !in
                    setOf(200, 201)
                ) {
                    return DriverIncidentNetworkOutcome.UnknownOutcome
                }
                val evidence = result.body.toEvidenceProjection()
                    ?: return DriverIncidentNetworkOutcome.UnknownOutcome
                if (evidence.subjectType != "DELIVERY_INCIDENT" ||
                    evidence.subjectId != incidentId ||
                    evidence.declaredContentType != declaredContentType ||
                    evidence.byteSize != byteSize
                ) {
                    return DriverIncidentNetworkOutcome.UnknownOutcome
                }
                if (evidence.checksumSha256 != null && evidence.checksumSha256 != checksumSha256) {
                    return DriverIncidentNetworkOutcome.Rejected("IDEMPOTENCY_PAYLOAD_CONFLICT")
                }
                DriverIncidentNetworkOutcome.EvidenceUploaded(evidence)
            }
        }
    }

    suspend fun evidenceStatus(evidenceId: String): DriverIncidentNetworkOutcome {
        if (!incidentUuid.matches(evidenceId)) return DriverIncidentNetworkOutcome.Unavailable
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, "$INCIDENT_EVIDENCE_BASE/$evidenceId")
            )
        ) {
            is ProtectedResult.Failure -> result.error.toIncidentOutcome()

            is ProtectedResult.Success -> {
                val evidence = result.body.toEvidenceProjection()
                    ?: return DriverIncidentNetworkOutcome.Unavailable
                if (evidence.id != evidenceId || evidence.subjectType != "DELIVERY_INCIDENT") {
                    DriverIncidentNetworkOutcome.Unavailable
                } else {
                    DriverIncidentNetworkOutcome.EvidenceStatus(evidence)
                }
            }
        }
    }

    suspend fun attachEvidence(
        deliveryId: String,
        attemptId: String,
        incidentId: String,
        evidenceId: String,
        expectedVersion: Long,
        idempotencyKey: String,
        frozenBody: String
    ): DriverIncidentNetworkOutcome {
        if (!incidentUuid.matches(deliveryId) || !incidentUuid.matches(attemptId) ||
            !incidentUuid.matches(incidentId) || !incidentUuid.matches(evidenceId) ||
            expectedVersion < 0 ||
            idempotencyKey.isBlank() || idempotencyKey.length > 160 ||
            !attachBodyMatches(frozenBody, evidenceId)
        ) {
            return DriverIncidentNetworkOutcome.Unavailable
        }
        val path =
            "$DRIVER_INCIDENT_BASE/$deliveryId/attempts/$attemptId/incidents/$incidentId/evidence"
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = path,
                    payload = frozenBody,
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"$expectedVersion\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toIncidentOutcome()

            is ProtectedResult.Success -> {
                if (result.status !in
                    setOf(200, 201)
                ) {
                    return DriverIncidentNetworkOutcome.UnknownOutcome
                }
                val incident =
                    result.body.toIncidentProjection()
                        ?: return DriverIncidentNetworkOutcome.UnknownOutcome
                val replayed = result.status == 200
                if (incident.id != incidentId || incident.deliveryId != deliveryId ||
                    incident.attemptId != attemptId ||
                    evidenceId !in incident.evidenceObjectIds ||
                    incident.deliveryVersion < expectedVersion ||
                    incident.replayed != replayed ||
                    result.etag.toVersion() != incident.deliveryVersion
                ) {
                    DriverIncidentNetworkOutcome.UnknownOutcome
                } else {
                    DriverIncidentNetworkOutcome.EvidenceAttached(incident)
                }
            }
        }
    }

    private fun bodyMatches(command: DriverIncidentWireCommand): Boolean = try {
        val body = incidentJson.parseToJsonElement(command.frozenBody).jsonObject
        body.keys == (
            if (command.type == null) {
                setOf("reason", "description", "place")
            } else {
                setOf("type", "reason", "description", "place")
            }
            ) &&
            (command.type == null || body.string("type") == command.type) &&
            body.string("reason") == command.reason &&
            body.string("description") == command.description &&
            body.string("place") == command.place
    } catch (_: Exception) {
        false
    }

    private fun String?.toIncidentProjection(): DriverIncidentProjection? = try {
        val root = this?.let(incidentJson::parseToJsonElement)?.jsonObject ?: return null
        val evidence =
            root["evidenceObjectIds"]?.jsonArray?.map {
                it.jsonPrimitive.contentOrNull
                    ?: return null
            }
                ?: return null
        if (evidence.size > 16 || evidence.distinct().size != evidence.size ||
            evidence.any { !incidentUuid.matches(it) }
        ) {
            return null
        }
        val recordedAt = root.string("recordedAt")
        Instant.parse(recordedAt)
        val version = root.long("deliveryVersion")
        require(version >= 0)
        val replayed = root["replayed"]?.jsonPrimitive?.booleanOrNull ?: return null
        val type = root.optionalString("type")
        val severity = root.optionalString("severity")
        val operationalExceptionId = root.optionalString("operationalExceptionId")
        if (type != null) require(type in INCIDENT_TYPES)
        if (severity != null) require(severity in INCIDENT_SEVERITIES)
        if (operationalExceptionId != null) require(incidentUuid.matches(operationalExceptionId))
        DriverIncidentProjection(
            id = root.string("id").also { require(incidentUuid.matches(it)) },
            deliveryId = root.string("deliveryId").also { require(incidentUuid.matches(it)) },
            attemptId = root.string("attemptId").also { require(incidentUuid.matches(it)) },
            reason = root.string("reason"),
            description = root.string("description"),
            place = root.string("place"),
            recordedByMembershipId = root.string("recordedByMembershipId").also {
                require(incidentUuid.matches(it))
            },
            recordedAt = recordedAt,
            evidenceObjectIds = evidence,
            deliveryVersion = version,
            replayed = replayed,
            type = type,
            severity = severity,
            operationalExceptionId = operationalExceptionId
        )
    } catch (_: Exception) {
        null
    }

    private fun String?.toEvidenceProjection(): DriverIncidentEvidenceProjection? = try {
        val root = this?.let(incidentJson::parseToJsonElement)?.jsonObject ?: return null
        val checksum = root.optionalString("checksumSha256")
        if (checksum != null && !checksum.matches(Regex("[0-9a-f]{64}"))) return null
        DriverIncidentEvidenceProjection(
            id = root.string("id").also { require(incidentUuid.matches(it)) },
            subjectType = root.string("subjectType"),
            subjectId = root.string("subjectId").also { require(incidentUuid.matches(it)) },
            lifecycleStatus = root.string("lifecycleStatus"),
            declaredContentType = root.string("declaredContentType"),
            checksumSha256 = checksum,
            byteSize = root.long("byteSize").also { require(it in 1..MAX_EVIDENCE_BYTES) }
        )
    } catch (_: Exception) {
        null
    }

    private fun attachBodyMatches(body: String, evidenceId: String): Boolean = try {
        val root = incidentJson.parseToJsonElement(body).jsonObject
        root.keys == setOf("evidenceObjectIds") &&
            root["evidenceObjectIds"] is JsonArray &&
            root["evidenceObjectIds"]!!.jsonArray.size == 1 &&
            root["evidenceObjectIds"]!!.jsonArray.single().jsonPrimitive.contentOrNull == evidenceId
    } catch (_: Exception) {
        false
    }

    private fun classificationMatches(
        command: DriverIncidentWireCommand,
        incident: DriverIncidentProjection
    ): Boolean = if (command.type != null) {
        incident.type == command.type &&
            incident.severity?.let(INCIDENT_SEVERITIES::contains) == true &&
            incident.operationalExceptionId != null
    } else {
        // A recovered pre-type idempotency intent may only return its original unclassified record.
        incident.type == null && incident.severity == null &&
            incident.operationalExceptionId == null
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("Driver incident response field is invalid")

    private fun JsonObject.optionalString(key: String): String? = when (val value = this[key]) {
        null, kotlinx.serialization.json.JsonNull -> null

        else -> value.jsonPrimitive.takeIf { it.isString }?.contentOrNull
            ?: error("Driver incident response field is invalid")
    }

    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.takeUnless { it.isString }?.contentOrNull?.toLongOrNull()
            ?: error("Driver incident response field is invalid")

    private fun String?.toVersion(): Long? = this?.trim()
        ?.takeIf { it.length >= 3 && it.first() == '"' && it.last() == '"' }
        ?.removeSurrounding("\"")?.toLongOrNull()?.takeIf { it >= 0 }

    private fun ClientFailure.toIncidentOutcome(): DriverIncidentNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired ->
            DriverIncidentNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            DriverIncidentNetworkOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure -> DriverIncidentNetworkOutcome.PermissionDenied

        kind == FailureKind.StaleState -> DriverIncidentNetworkOutcome.StaleVersion

        kind == FailureKind.ResourceUnavailable -> DriverIncidentNetworkOutcome.NotFound

        kind == FailureKind.ValidationFailure || kind == FailureKind.BusinessConflict ||
            kind == FailureKind.PreconditionRequired -> DriverIncidentNetworkOutcome.Rejected(
            problemCode
        )

        else -> DriverIncidentNetworkOutcome.UnknownOutcome
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
        const val MAX_EVIDENCE_BYTES = 10L * 1024L * 1024L
        val ALLOWED_EVIDENCE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val INCIDENT_TYPES = setOf(
            "DELAY",
            "INCOMPLETE_INSTRUCTION",
            "ACCESS_BLOCKED",
            "CUSTOMER_UNAVAILABLE",
            "DELIVERY_NOT_EXECUTABLE",
            "TEMPERATURE_EXCURSION",
            "SAFETY_COMPROMISING_DAMAGE"
        )
        val INCIDENT_SEVERITIES = setOf("WARNING", "BLOCKING", "CRITICAL")
    }
}

data class DriverIncidentWireCommand(
    val deliveryId: String,
    val attemptId: String,
    val expectedVersion: Long,
    val idempotencyKey: String,
    val reason: String,
    val description: String,
    val place: String,
    val frozenBody: String,
    /** Null only for an exact recovered command created before typed reporting was introduced. */
    val type: String? = null
)
