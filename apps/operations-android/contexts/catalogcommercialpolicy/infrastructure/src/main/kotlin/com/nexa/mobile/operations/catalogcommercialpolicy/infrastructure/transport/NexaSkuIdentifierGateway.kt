package com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val SKU_RESOLUTION_PATH = "/api/v1/skus/resolve"
private const val MAX_IDENTIFIER_LENGTH = 160
private val skuResolutionJson = Json { ignoreUnknownKeys = true }

enum class SkuIdentifierType { SKU_CODE, GTIN, SKU_CODE_AND_GTIN }

/** Server-confirmed fields returned by the read-only physical identifier resolver. */
data class ResolvedSkuIdentifierProjection(
    val skuId: UUID,
    val skuCode: String,
    val gtin: String?,
    val presentation: String,
    val unitOfMeasure: String?,
    val status: String,
    val identifierType: SkuIdentifierType
) {
    override fun toString(): String =
        "ResolvedSkuIdentifierProjection(skuId=REDACTED, skuCode=REDACTED, status=$status)"
}

sealed interface SkuIdentifierResolutionOutcome {
    data class Resolved(val sku: ResolvedSkuIdentifierProjection) :
        SkuIdentifierResolutionOutcome

    data class NotFound(val identifierType: SkuIdentifierType) : SkuIdentifierResolutionOutcome

    data class Ambiguous(val candidateCount: Int, val identifierType: SkuIdentifierType) :
        SkuIdentifierResolutionOutcome

    data object InvalidIdentifier : SkuIdentifierResolutionOutcome
    data object NetworkUnavailable : SkuIdentifierResolutionOutcome
    data object ServiceUnavailable : SkuIdentifierResolutionOutcome
    data object PermissionDenied : SkuIdentifierResolutionOutcome
    data object ContextInvalidated : SkuIdentifierResolutionOutcome
    data object SessionExpired : SkuIdentifierResolutionOutcome
}

/** Protected read adapter for exact SKU-code and GTIN resolution. */
class NexaSkuIdentifierGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun resolve(identifier: String): SkuIdentifierResolutionOutcome {
        val normalized = identifier.trim()
        if (normalized.isEmpty() || normalized.length > MAX_IDENTIFIER_LENGTH) {
            return SkuIdentifierResolutionOutcome.InvalidIdentifier
        }
        val encodedIdentifier = URLEncoder.encode(normalized, StandardCharsets.UTF_8.name())
        val path = "$SKU_RESOLUTION_PATH?identifier=$encodedIdentifier"
        return when (
            val result = protectedCalls.execute(ProtectedRequest(ProtectedMethod.GET, path))
        ) {
            is ProtectedResult.Failure -> result.error.toResolutionOutcome()
            is ProtectedResult.Success -> parseResolution(result.body, normalized)
        }
    }

    private fun parseResolution(
        body: String?,
        requestedIdentifier: String
    ): SkuIdentifierResolutionOutcome {
        val response = body.decode<SkuIdentifierResolutionWire>()
            ?: return SkuIdentifierResolutionOutcome.ServiceUnavailable
        val outcome = response.outcome.requiredText()
            ?: return SkuIdentifierResolutionOutcome.ServiceUnavailable
        val identifierType = response.identifierType.toIdentifierType()
            ?: return SkuIdentifierResolutionOutcome.ServiceUnavailable
        val normalized = response.normalizedIdentifier.requiredText()
            ?: return SkuIdentifierResolutionOutcome.ServiceUnavailable
        val candidateCount = response.candidateCount
            ?: return SkuIdentifierResolutionOutcome.ServiceUnavailable
        if (normalized != requestedIdentifier || candidateCount < 0) {
            return SkuIdentifierResolutionOutcome.ServiceUnavailable
        }

        return when (outcome) {
            "RESOLVED" -> parseResolved(response, candidateCount, identifierType)

            "NOT_FOUND" -> if (candidateCount == 0 && response.hasNoSkuProjection()) {
                SkuIdentifierResolutionOutcome.NotFound(identifierType)
            } else {
                SkuIdentifierResolutionOutcome.ServiceUnavailable
            }

            "AMBIGUOUS" -> if (candidateCount > 1 && response.hasNoSkuProjection()) {
                SkuIdentifierResolutionOutcome.Ambiguous(candidateCount, identifierType)
            } else {
                SkuIdentifierResolutionOutcome.ServiceUnavailable
            }

            else -> SkuIdentifierResolutionOutcome.ServiceUnavailable
        }
    }

    private fun parseResolved(
        response: SkuIdentifierResolutionWire,
        candidateCount: Int,
        identifierType: SkuIdentifierType
    ): SkuIdentifierResolutionOutcome {
        if (candidateCount != 1) return SkuIdentifierResolutionOutcome.ServiceUnavailable
        val rawSkuId = response.skuId.requiredText()
            ?: return SkuIdentifierResolutionOutcome.ServiceUnavailable
        val skuId = runCatching { UUID.fromString(rawSkuId) }.getOrNull()
            ?.takeIf { it.toString().equals(rawSkuId, ignoreCase = true) }
            ?: return SkuIdentifierResolutionOutcome.ServiceUnavailable
        val skuCode = response.skuCode.requiredText()
            ?: return SkuIdentifierResolutionOutcome.ServiceUnavailable
        val presentation = response.presentation.requiredText()
            ?: return SkuIdentifierResolutionOutcome.ServiceUnavailable
        val status = response.status.requiredText()
            ?: return SkuIdentifierResolutionOutcome.ServiceUnavailable
        val gtin = response.gtin.optionalText()
        val unitOfMeasure = response.unitOfMeasure.optionalText()
        return SkuIdentifierResolutionOutcome.Resolved(
            ResolvedSkuIdentifierProjection(
                skuId = skuId,
                skuCode = skuCode,
                gtin = gtin,
                presentation = presentation,
                unitOfMeasure = unitOfMeasure,
                status = status,
                identifierType = identifierType
            )
        )
    }

    private fun ClientFailure.toResolutionOutcome(): SkuIdentifierResolutionOutcome = when {
        httpStatus == 400 -> SkuIdentifierResolutionOutcome.InvalidIdentifier

        kind == FailureKind.AuthenticationRequired -> SkuIdentifierResolutionOutcome.SessionExpired

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            SkuIdentifierResolutionOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure -> SkuIdentifierResolutionOutcome.PermissionDenied

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            SkuIdentifierResolutionOutcome.NetworkUnavailable

        else -> SkuIdentifierResolutionOutcome.ServiceUnavailable
    }

    private fun SkuIdentifierResolutionWire.hasNoSkuProjection(): Boolean =
        skuId == null && skuCode == null && gtin == null && presentation == null &&
            unitOfMeasure == null && status == null

    private fun String?.toIdentifierType(): SkuIdentifierType? = when (this) {
        "SKU_CODE" -> SkuIdentifierType.SKU_CODE
        "GTIN" -> SkuIdentifierType.GTIN
        "SKU_CODE_AND_GTIN" -> SkuIdentifierType.SKU_CODE_AND_GTIN
        else -> null
    }

    private fun String?.requiredText(): String? = this?.takeIf(String::isNotBlank)

    private fun String?.optionalText(): String? = this?.takeIf(String::isNotBlank)

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { skuResolutionJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
    }
}

@Serializable
private data class SkuIdentifierResolutionWire(
    val outcome: String? = null,
    val identifierType: String? = null,
    val normalizedIdentifier: String? = null,
    val candidateCount: Int? = null,
    val skuId: String? = null,
    val skuCode: String? = null,
    val gtin: String? = null,
    val presentation: String? = null,
    val unitOfMeasure: String? = null,
    val status: String? = null
)
