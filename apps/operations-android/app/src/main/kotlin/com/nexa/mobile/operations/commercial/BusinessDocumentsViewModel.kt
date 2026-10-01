package com.nexa.mobile.operations.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BusinessDocumentIdentity(val id: String, val customerId: String, val subjectType: String,
    val subjectId: String, val number: String?, val type: String, val version: Long, val generatedAt: String?,
    val checksum: String, val byteSize: Long)
data class BusinessDocumentContent(val identity: BusinessDocumentIdentity, val bytes: ByteArray) {
    override fun toString(): String = "BusinessDocumentContent(REDACTED)"
}
data class BusinessDocumentsState(val items: List<BusinessDocumentIdentity> = emptyList(), val page: Int = 0,
    val totalItems: Long? = null, val status: String = "NotRequested", val content: BusinessDocumentContent? = null)
sealed interface BusinessDocumentsResult {
    data class Page(val items: List<BusinessDocumentIdentity>, val totalItems: Long) : BusinessDocumentsResult
    data class Content(val value: BusinessDocumentContent) : BusinessDocumentsResult
    data object Denied : BusinessDocumentsResult
    data object Unavailable : BusinessDocumentsResult
}
interface BusinessDocumentsGateway {
    suspend fun list(authority: CommercialAuthority, page: Int): BusinessDocumentsResult
    suspend fun content(authority: CommercialAuthority, id: String): BusinessDocumentsResult
}
class BusinessDocumentsViewModel(private val gateway: BusinessDocumentsGateway) : ViewModel() {
    private val mutableState = MutableStateFlow(BusinessDocumentsState())
    val state = mutableState.asStateFlow()
    private var authority: CommercialAuthority? = null
    private var generation = 0L
    fun deactivate() { authority = null; generation++; mutableState.value = BusinessDocumentsState() }
    fun activate(value: CommercialAuthority) { deactivate(); authority = value; refresh() }
    fun refresh() = load(state.value.page)
    fun nextPage() { val s = state.value; if (s.status == "Current" && (s.page + 1) * 25L < (s.totalItems ?: 0)) load(s.page + 1) }
    fun previousPage() { if (state.value.page > 0 && state.value.status == "Current") load(state.value.page - 1) }
    private fun load(page: Int) = perform(BusinessDocumentsState(page = page)) { gateway.list(it, page) }
    fun open(id: String) {
        if (state.value.status != "Current" || state.value.items.none { it.id == id }) return
        perform(state.value.copy(content = null)) { gateway.content(it, id) }
    }
    fun closeContent() { mutableState.value = state.value.copy(content = null) }
    private fun perform(input: BusinessDocumentsState, action: suspend (CommercialAuthority) -> BusinessDocumentsResult) {
        val captured = authority ?: return
        val request = ++generation
        mutableState.value = input.copy(status = "Pending", content = null)
        viewModelScope.launch {
            val result = try { action(captured) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { BusinessDocumentsResult.Unavailable }
            if (authority != captured || generation != request) return@launch
            mutableState.value = when (result) {
                is BusinessDocumentsResult.Page -> input.copy(items = result.items, totalItems = result.totalItems, status = "Current")
                is BusinessDocumentsResult.Content -> input.copy(content = result.value, status = "Current")
                BusinessDocumentsResult.Denied -> BusinessDocumentsState(status = "PermissionDenied")
                BusinessDocumentsResult.Unavailable -> input.copy(status = "Unavailable", content = null)
            }
        }
    }
}
