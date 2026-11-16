package com.nexa.mobile.operations.salescommitment.application.commercial

/** Verified Sales order scope projected by the Operations composition root. */
data class SalesOrderExportAuthority(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>,
    val authorityEpoch: Long
) {
    val canReadOrderExports: Boolean
        get() = authorityEpoch > 0 && "order_export.read" in permissions

    override fun toString(): String = "SalesOrderExportAuthority(REDACTED)"
}

enum class SalesOrderExportFormat(val extension: String, val mediaType: String) {
    PDF("pdf", "application/pdf"),
    CSV("csv", "text/csv")
}

/** Opaque SAF destination; URI details stay out of application logs. */
data class SalesExportDestination(val value: String) {
    override fun toString(): String = "SalesExportDestination(REDACTED)"
}

enum class SalesOrderExportSaveResult {
    Saved,
    PermissionDenied,
    ContextInvalidated,
    Unavailable
}

interface SalesOrderExportGateway {
    suspend fun save(
        authority: SalesOrderExportAuthority,
        orderId: String,
        format: SalesOrderExportFormat,
        destination: SalesExportDestination
    ): SalesOrderExportSaveResult
}
