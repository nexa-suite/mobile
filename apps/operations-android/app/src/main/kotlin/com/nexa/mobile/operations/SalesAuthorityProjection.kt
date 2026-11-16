package com.nexa.mobile.operations

import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.SalesMessagingAuthority
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority

/** Maps verified access facts into BC-owned immutable application inputs. */
internal fun VerifiedContextAuthority.toSalesMessagingAuthority(epoch: Long) =
    SalesMessagingAuthority(
        userId = userId,
        tenantId = tenantId,
        workspaceId = workspaceId,
        membershipId = membershipId,
        permissions = permissions.toSet(),
        authorityEpoch = epoch
    )

internal fun VerifiedContextAuthority.toSalesOrderExportAuthority(epoch: Long) =
    SalesOrderExportAuthority(
        userId = userId,
        tenantId = tenantId,
        workspaceId = workspaceId,
        membershipId = membershipId,
        permissions = permissions.toSet(),
        authorityEpoch = epoch
    )
