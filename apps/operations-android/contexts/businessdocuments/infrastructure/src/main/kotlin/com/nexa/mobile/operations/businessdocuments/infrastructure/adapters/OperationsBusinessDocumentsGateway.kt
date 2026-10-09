package com.nexa.mobile.operations.businessdocuments.infrastructure.adapters

import com.nexa.mobile.operations.businessdocuments.application.commercial.BusinessDocumentsGateway
import com.nexa.mobile.operations.businessdocuments.application.commercial.BusinessDocumentsResult
import com.nexa.mobile.operations.businessdocuments.domain.model.commercial.BusinessDocumentContent
import com.nexa.mobile.operations.businessdocuments.domain.model.commercial.BusinessDocumentIdentity
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class OperationsBusinessDocumentsGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val calls: ProtectedCallExecutor
) : BusinessDocumentsGateway {
    private fun current(a: CommercialAuthority, permission: String): Boolean {
        val v = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active && v.hasAuthorizedContext &&
            a.authorityEpoch > 0 &&
            permission in a.permissions && v.permissions == a.permissions && v.userId == a.userId &&
            v.tenantId == a.tenantId && v.workspaceId == a.workspaceId &&
            v.membershipId == a.membershipId
    }
    override suspend fun list(authority: CommercialAuthority, page: Int): BusinessDocumentsResult {
        if (page < 0 || !current(authority, "document.read")) return BusinessDocumentsResult.Denied
        val lease = sessions.currentAccess() ?: return BusinessDocumentsResult.Denied
        val result = calls.execute(
            ProtectedRequest(
                ProtectedMethod.GET,
                "/api/v1/business-documents?status=ISSUED&page=$page&size=25"
            )
        )
        if (!current(authority, "document.read") ||
            !sessions.isEpochCurrent(lease.epoch)
        ) {
            return BusinessDocumentsResult.Denied
        }
        if (result !is ProtectedResult.Success) return failure(result)
        val body = Json.parseToJsonElement(result.body ?: "").jsonObject
        val items = body.getValue("items").jsonArray.map {
            identity(it.jsonObject)
                ?: return BusinessDocumentsResult.Unavailable
        }
        return BusinessDocumentsResult.Page(
            items,
            body.getValue("total").jsonPrimitive.long.also {
                require(it >= items.size)
            }
        )
    }
    override suspend fun content(
        authority: CommercialAuthority,
        id: String
    ): BusinessDocumentsResult {
        if (runCatching { UUID.fromString(id) }.isFailure || !current(authority, "document.read") ||
            !current(authority, "document.download")
        ) {
            return BusinessDocumentsResult.Denied
        }
        val lease = sessions.currentAccess() ?: return BusinessDocumentsResult.Denied
        val metadata = calls.execute(
            ProtectedRequest(ProtectedMethod.GET, "/api/v1/business-documents/$id")
        )
        if (metadata !is ProtectedResult.Success) return failure(metadata)
        val identity =
            identity(Json.parseToJsonElement(metadata.body ?: "").jsonObject)
                ?: return BusinessDocumentsResult.Unavailable
        if (identity.id != id || identity.byteSize !in 1..8L * 1024 * 1024 ||
            !current(authority, "document.download") ||
            !current(authority, "document.read") ||
            !sessions.isEpochCurrent(lease.epoch)
        ) {
            return BusinessDocumentsResult.Denied
        }
        val downloadPath = "/api/v1/business-documents/$id/downloads"
        if (!Regex("/api/v1/business-documents/[0-9a-fA-F-]{36}/downloads").matches(downloadPath)) {
            return BusinessDocumentsResult.Denied
        }
        val content = calls.execute(
            ProtectedRequest(
                ProtectedMethod.GET,
                downloadPath,
                binaryResponse = true
            )
        )
        if (!current(authority, "document.download") || !current(authority, "document.read") ||
            !sessions.isEpochCurrent(lease.epoch)
        ) {
            return BusinessDocumentsResult.Denied
        }
        if (content !is ProtectedResult.Success) return failure(content)
        val bytes = content.bytes ?: return BusinessDocumentsResult.Unavailable
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
        if (content.status != 200 ||
            content.contentType?.substringBefore(';') != identity.contentType ||
            bytes.size.toLong() != identity.byteSize || !hash.equals(identity.checksum, true) ||
            !hash.equals(content.checksumSha256, true)
        ) {
            return BusinessDocumentsResult.Unavailable
        }
        return BusinessDocumentsResult.Content(BusinessDocumentContent(identity, bytes))
    }
    private fun identity(value: JsonObject): BusinessDocumentIdentity? = runCatching {
        fun text(key: String) = value.getValue(key).jsonPrimitive.content
        require(text("status") == "ISSUED")
        val formats = mapOf(
            "PDF" to "application/pdf",
            "CSV" to "text/csv",
            "XML" to "application/xml"
        )
        require(formats[text("format")] == text("contentType"))
        UUID.fromString(text("id"))
        UUID.fromString(text("clientAccountId"))
        UUID.fromString(text("subjectId"))
        require(Regex("[0-9a-fA-F]{64}").matches(text("checksumSha256")))
        BusinessDocumentIdentity(
            text("id"), text("clientAccountId"), text("subjectType"), text("subjectId"),
            value["documentNumber"]?.jsonPrimitive?.contentOrNull,
            text(
                "documentType"
            ),
            value.getValue("version").jsonPrimitive.long,
            value["generatedAt"]?.jsonPrimitive?.contentOrNull,
            text(
                "checksumSha256"
            ),
            value.getValue("byteSize").jsonPrimitive.long, text("format"), text("contentType")
        )
    }.getOrNull()
    private fun failure(result: ProtectedResult) = if (result is ProtectedResult.Failure &&
        result.error.httpStatus in setOf(401, 403, 404)
    ) {
        BusinessDocumentsResult.Denied
    } else {
        BusinessDocumentsResult.Unavailable
    }
}
