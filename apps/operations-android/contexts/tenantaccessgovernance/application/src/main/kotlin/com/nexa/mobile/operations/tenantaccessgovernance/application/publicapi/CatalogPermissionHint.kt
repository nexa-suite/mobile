package com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi

import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.PermissionHint

fun catalogReadHint(permissions: Set<String>): PermissionHint = when {
    "catalog.read" in permissions || "catalog:read" in permissions -> PermissionHint.Available
    permissions.isEmpty() -> PermissionHint.Unknown
    else -> PermissionHint.Unavailable
}
