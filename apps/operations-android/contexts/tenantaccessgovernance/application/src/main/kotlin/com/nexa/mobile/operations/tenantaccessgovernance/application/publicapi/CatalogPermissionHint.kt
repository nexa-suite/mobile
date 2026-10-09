package com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi

import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.PermissionHint as DomainPermissionHint
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.WorkforceContextSummary

/** Read-only permission projection published for client context consumers. */
enum class PermissionHint { Available, Unavailable, Unknown }

fun catalogReadHint(permissions: Set<String>): PermissionHint = when {
    "catalog.read" in permissions || "catalog:read" in permissions -> PermissionHint.Available
    permissions.isEmpty() -> PermissionHint.Unknown
    else -> PermissionHint.Unavailable
}

/** Projects BC-01's internal context hint into its stable client-facing type. */
fun WorkforceContextSummary.permissionHintProjection(): PermissionHint = when (permissionHint) {
    DomainPermissionHint.Available -> PermissionHint.Available
    DomainPermissionHint.Unavailable -> PermissionHint.Unavailable
    DomainPermissionHint.Unknown -> PermissionHint.Unknown
}
