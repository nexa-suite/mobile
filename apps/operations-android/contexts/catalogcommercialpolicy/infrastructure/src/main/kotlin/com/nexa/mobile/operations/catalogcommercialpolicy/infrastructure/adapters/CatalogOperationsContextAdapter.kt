package com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters

import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsContext
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsIdentity
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.ActiveOperationsContext

/** Maps the verified workforce scope into Catalog's own context projection. */
object CatalogOperationsContextAdapter {
    fun from(context: ActiveOperationsContext): CatalogOperationsContext = CatalogOperationsContext(
        companyName = context.companyName,
        workspaceName = context.workspaceName,
        authorityEpoch = context.authorityEpoch,
        verifiedIdentity = context.verifiedIdentity?.let { identity ->
            CatalogOperationsIdentity(
                userId = identity.userId,
                tenantId = identity.tenantId,
                workspaceId = identity.workspaceId,
                membershipId = identity.membershipId,
                permissions = identity.permissions.toSet()
            )
        }
    )
}
