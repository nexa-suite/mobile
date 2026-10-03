package com.nexa.mobile.operations.core.local.scoped

import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidScopedMetadataStoreTest {
    @Test fun reconstructedStorePreservesPayloadOnlyForSamePurposeAndFullScope() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scope =
            ScopedMetadataScope(UUID.randomUUID().toString(), "tenant", "workspace", "member")
        val writer = AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DriverAttemptStart)
        try {
            assertTrue(writer.save(scope, "frozen-command-key-and-body"))
            val restored = AndroidScopedMetadataStore(
                context,
                ScopedMetadataPurpose.DriverAttemptStart
            ).load(scope)
            assertEquals(
                "frozen-command-key-and-body",
                (restored as ScopedMetadataRead.Value).payload
            )
            for (other in listOf(
                scope.copy(userId = "other"),
                scope.copy(tenantId = "other"),
                scope.copy(workspaceId = "other"),
                scope.copy(membershipId = "other")
            )) {
                assertNull((writer.load(other) as ScopedMetadataRead.Value).payload)
            }
            assertNull(
                (
                    AndroidScopedMetadataStore(
                        context,
                        ScopedMetadataPurpose.FieldPurchaseRequest
                    ).load(scope) as ScopedMetadataRead.Value
                    ).payload
            )
            assertTrue(writer.clear(scope))
            assertNull((writer.load(scope) as ScopedMetadataRead.Value).payload)
        } finally {
            writer.clear(scope)
        }
    }

    @Test fun oversizedPayloadCannotReplaceExistingFrozenRecord() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scope =
            ScopedMetadataScope(UUID.randomUUID().toString(), "tenant", "workspace", "member")
        val store = AndroidScopedMetadataStore(context, ScopedMetadataPurpose.FieldPurchaseRequest)
        try {
            assertTrue(store.save(scope, "frozen"))
            assertEquals(false, store.save(scope, "x".repeat(64 * 1024 + 1)))
            assertEquals("frozen", (store.load(scope) as ScopedMetadataRead.Value).payload)
        } finally {
            store.clear(scope)
        }
    }
}
