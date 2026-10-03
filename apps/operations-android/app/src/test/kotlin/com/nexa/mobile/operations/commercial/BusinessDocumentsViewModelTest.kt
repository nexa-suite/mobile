package com.nexa.mobile.operations.commercial

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessDocumentsViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val authority =
        CommercialAuthority("u", "t", "w", "m", setOf("document.read", "document.download"), 7)
    private val document =
        BusinessDocumentIdentity(
            "doc", "customer", "PURCHASE_REQUEST", "request", "PR-1", "CONFIRMATION", 1,
            "2026-09-30T00:00:00Z", "checksum", 4
        )

    @Test fun revokedDownloadClearsPrivateListAndContent() = runTest {
        val model = BusinessDocumentsViewModel(object : BusinessDocumentsGateway {
            override suspend fun list(authority: CommercialAuthority, page: Int) =
                BusinessDocumentsResult.Page(listOf(document), 1)
            override suspend fun content(authority: CommercialAuthority, id: String) =
                BusinessDocumentsResult.Denied
        })
        model.activate(authority)
        runCurrent()
        model.open("doc")
        runCurrent()
        assertEquals("PermissionDenied", model.state.value.status)
        assertTrue(model.state.value.items.isEmpty())
        assertNull(model.state.value.content)
    }

    @Test fun latePrivateContentAfterRouteLossCannotReappear() = runTest {
        val pending = CompletableDeferred<BusinessDocumentsResult>()
        val model = BusinessDocumentsViewModel(object : BusinessDocumentsGateway {
            override suspend fun list(authority: CommercialAuthority, page: Int) =
                BusinessDocumentsResult.Page(listOf(document), 1)
            override suspend fun content(authority: CommercialAuthority, id: String) =
                pending.await()
        })
        model.activate(authority)
        runCurrent()
        model.open("doc")
        runCurrent()
        model.deactivate()
        pending.complete(
            BusinessDocumentsResult.Content(
                BusinessDocumentContent(document, byteArrayOf(1, 2, 3, 4))
            )
        )
        runCurrent()
        assertEquals("NotRequested", model.state.value.status)
        assertNull(model.state.value.content)
        assertTrue(model.state.value.items.isEmpty())
    }

    @Test fun unavailableDocumentCannotCreateContentOrOpenUnlistedIdentity() = runTest {
        var downloads = 0
        val model = BusinessDocumentsViewModel(object : BusinessDocumentsGateway {
            override suspend fun list(authority: CommercialAuthority, page: Int) =
                BusinessDocumentsResult.Page(emptyList(), 0)
            override suspend fun content(
                authority: CommercialAuthority,
                id: String
            ): BusinessDocumentsResult {
                downloads++
                return BusinessDocumentsResult.Unavailable
            }
        })
        model.activate(authority)
        runCurrent()
        model.open("unlisted")
        runCurrent()
        assertEquals(0, downloads)
        assertNull(model.state.value.content)
        assertEquals(0L, model.state.value.totalItems)
    }
}
