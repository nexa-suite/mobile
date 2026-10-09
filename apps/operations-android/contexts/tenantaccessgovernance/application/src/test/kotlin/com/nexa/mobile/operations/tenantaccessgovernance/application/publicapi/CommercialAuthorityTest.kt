package com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommercialAuthorityTest {
    @Test
    fun clientReadPermissionsAreHintsOnlyWhenEpochAndAllScopeIdsArePresent() {
        assertTrue(authority(permissions = setOf("client.read")).canReadCustomers())
        assertTrue(authority(permissions = setOf("sales:read")).canReadCustomers())
        assertFalse(authority(permissions = setOf("catalog.read")).canReadCustomers())
        assertFalse(authority(permissions = setOf("client.read"), epoch = 0).canReadCustomers())
        assertFalse(authority(permissions = setOf("client.read"), epoch = -1).canReadCustomers())

        assertFalse(authority(permissions = setOf("client.read"), userId = "").canReadCustomers())
        assertFalse(
            authority(permissions = setOf("client.read"), tenantId = " ").canReadCustomers()
        )
        assertFalse(
            authority(permissions = setOf("client.read"), workspaceId = "\t")
                .canReadCustomers()
        )
        assertFalse(
            authority(permissions = setOf("client.read"), membershipId = "\n")
                .canReadCustomers()
        )
    }

    private fun authority(
        permissions: Set<String>,
        epoch: Long = 1,
        userId: String = "user-1",
        tenantId: String = "tenant-1",
        workspaceId: String = "workspace-1",
        membershipId: String = "membership-1"
    ) = CommercialAuthority(
        userId = userId,
        tenantId = tenantId,
        workspaceId = workspaceId,
        membershipId = membershipId,
        permissions = permissions,
        authorityEpoch = epoch
    )
}
