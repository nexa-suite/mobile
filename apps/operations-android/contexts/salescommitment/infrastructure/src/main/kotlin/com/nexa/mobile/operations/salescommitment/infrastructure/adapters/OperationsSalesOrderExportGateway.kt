package com.nexa.mobile.operations.salescommitment.infrastructure.adapters

import android.content.Context
import android.net.Uri
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesExportDestination
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportAuthority
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportFormat
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportSaveResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OperationsSalesOrderExportGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionCoordinator,
    private val calls: ProtectedCallExecutor
) : SalesOrderExportGateway {
    override suspend fun save(
        authority: SalesOrderExportAuthority,
        orderId: String,
        format: SalesOrderExportFormat,
        destination: SalesExportDestination
    ): SalesOrderExportSaveResult {
        if (!validId(orderId)) return SalesOrderExportSaveResult.Unavailable
        if (!current(authority)) return SalesOrderExportSaveResult.PermissionDenied
        val uri = runCatching { Uri.parse(destination.value) }.getOrNull()
            ?.takeIf { it.scheme == "content" && !it.authority.isNullOrBlank() }
            ?: return SalesOrderExportSaveResult.Unavailable
        val lease = sessions.currentAccess() ?: return SalesOrderExportSaveResult.ContextInvalidated
        return try {
            val response = calls.execute(
                ProtectedRequest(
                    ProtectedMethod.GET,
                    "/api/v1/sales-orders/$orderId/exports/summary.${format.extension}",
                    binaryResponse = true
                )
            )
            if (!current(authority) || !sessions.isEpochCurrent(lease.epoch)) {
                return SalesOrderExportSaveResult.ContextInvalidated
            }
            val bytes = salesExportContent(response, format)
                ?: return response.toSaveResult()
            if (!current(authority) || !sessions.isEpochCurrent(lease.epoch)) {
                bytes.fill(0)
                return SalesOrderExportSaveResult.ContextInvalidated
            }
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: return@withContext SalesOrderExportSaveResult.Unavailable
                    output.use { it.write(bytes) }
                    SalesOrderExportSaveResult.Saved
                }
            } catch (_: IOException) {
                SalesOrderExportSaveResult.Unavailable
            } catch (_: SecurityException) {
                SalesOrderExportSaveResult.Unavailable
            } finally {
                bytes.fill(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SalesOrderExportSaveResult.Unavailable
        }
    }

    private fun current(authority: SalesOrderExportAuthority): Boolean {
        val verified = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active &&
            authority.canReadOrderExports && verified.hasAuthorizedContext &&
            verified.userId == authority.userId && verified.tenantId == authority.tenantId &&
            verified.workspaceId == authority.workspaceId &&
            verified.membershipId == authority.membershipId &&
            verified.permissions == authority.permissions
    }

    private fun validId(value: String) = runCatching { UUID.fromString(value) }.isSuccess

    private fun ProtectedResult.toSaveResult() = when (this) {
        is ProtectedResult.Success -> SalesOrderExportSaveResult.Unavailable

        is ProtectedResult.Failure -> when (error.kind) {
            FailureKind.AuthenticationRequired -> SalesOrderExportSaveResult.ContextInvalidated
            FailureKind.AuthorizationFailure -> SalesOrderExportSaveResult.PermissionDenied
            else -> SalesOrderExportSaveResult.Unavailable
        }
    }
}

internal fun salesExportContent(
    result: ProtectedResult,
    format: SalesOrderExportFormat
): ByteArray? {
    if (result !is ProtectedResult.Success) return null
    val source = result.bytes ?: return null
    if (result.status != 200 ||
        result.contentType?.substringBefore(';')?.trim()?.lowercase() != format.mediaType ||
        source.isEmpty() || source.size > MAX_EXPORT_BYTES
    ) {
        source.fill(0)
        return null
    }
    return source.copyOf().also { source.fill(0) }
}

private const val MAX_EXPORT_BYTES = 8 * 1024 * 1024
