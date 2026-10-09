package com.nexa.mobile.operations.core.auth.session

fun contextIsCurrent(expected: VerifiedSession, current: VerifiedSession): Boolean =
    current.hasAuthorizedContext && current.userId == expected.userId &&
        current.tenantId == expected.tenantId && current.workspaceId == expected.workspaceId &&
        current.membershipId == expected.membershipId &&
        current.permissions == expected.permissions
