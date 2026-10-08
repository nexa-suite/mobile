package com.nexa.mobile.operations

import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.catalogReadHint
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.PermissionHint
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogPermissionHintTest {
    @Test
    fun catalogReadMakesTaskVisible() {
        assertEquals(PermissionHint.Available, catalogReadHint(setOf("catalog.read")))
        assertEquals(PermissionHint.Available, catalogReadHint(setOf("catalog:read")))
    }

    @Test
    fun warehouseInventoryAndWildcardNeverGrantCatalogHint() {
        for (
        permissions in listOf(
            setOf("warehouse.read"),
            setOf("inventory.read"),
            setOf("warehouse.read", "inventory.read"),
            setOf("*"),
            setOf("notification.read")
        )
        ) {
            assertEquals(PermissionHint.Unavailable, catalogReadHint(permissions))
        }
    }

    @Test
    fun missingProjectionIsUnknown() {
        assertEquals(PermissionHint.Unknown, catalogReadHint(emptySet()))
    }
}
