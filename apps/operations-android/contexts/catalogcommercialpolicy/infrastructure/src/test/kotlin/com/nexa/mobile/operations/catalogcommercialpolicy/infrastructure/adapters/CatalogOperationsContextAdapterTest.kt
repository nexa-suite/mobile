package com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters

import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsIdentity
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.ActiveOperationsContext
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.VerifiedOperationsIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogOperationsContextAdapterTest {
    @Test
    fun mapsExactNamesEpochAndVerifiedWorkforceScopeIntoCatalogProjection() {
        val permissions = linkedSetOf("catalog.read", "warehouse.read")
        val activeContext = ActiveOperationsContext(
            companyName = "Company",
            workspaceName = "Warehouse",
            authorityEpoch = 17,
            verifiedIdentity = VerifiedOperationsIdentity(
                userId = "user-1",
                tenantId = "tenant-2",
                workspaceId = "workspace-3",
                membershipId = "membership-4",
                permissions = permissions
            )
        )

        val catalogContext = CatalogOperationsContextAdapter.from(activeContext)
        permissions += "late.permission"

        assertEquals("Company", catalogContext.companyName)
        assertEquals("Warehouse", catalogContext.workspaceName)
        assertEquals(17L, catalogContext.authorityEpoch)
        assertEquals(
            CatalogOperationsIdentity(
                userId = "user-1",
                tenantId = "tenant-2",
                workspaceId = "workspace-3",
                membershipId = "membership-4",
                permissions = setOf("catalog.read", "warehouse.read")
            ),
            catalogContext.verifiedIdentity
        )
        assertNotSame(permissions, catalogContext.verifiedIdentity?.permissions)
    }

    @Test
    fun preservesMissingVerifiedIdentityAsMissing() {
        val catalogContext = CatalogOperationsContextAdapter.from(
            ActiveOperationsContext(
                companyName = "Company",
                workspaceName = "Warehouse",
                authorityEpoch = 0,
                verifiedIdentity = null
            )
        )

        assertNull(catalogContext.verifiedIdentity)
        assertEquals(0L, catalogContext.authorityEpoch)
    }
}
