package com.nexa.mobile.operations.businessdocuments.presentation.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.businessdocuments.application.commercial.BusinessDocumentsGateway
import com.nexa.mobile.operations.businessdocuments.application.commercial.BusinessDocumentsResult
import com.nexa.mobile.operations.businessdocuments.domain.model.commercial.BusinessDocumentContent
import com.nexa.mobile.operations.businessdocuments.domain.model.commercial.BusinessDocumentIdentity
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BusinessDocumentsState(
    val items: List<BusinessDocumentIdentity> = emptyList(),
    val page: Int = 0,
    val totalItems: Long? = null,
    val status: String = "NotRequested",
    val content: BusinessDocumentContent? = null,
    val contentStatus: String = "NotRequested",
    val canDownloadContent: Boolean = false
)

class BusinessDocumentsViewModel(private val gateway: BusinessDocumentsGateway) : ViewModel() {
    private val mutableState = MutableStateFlow(BusinessDocumentsState())
    val state = mutableState.asStateFlow()
    private var authority: CommercialAuthority? = null
    private var generation = 0L
    fun deactivate() {
        authority = null
        generation++
        mutableState.value = BusinessDocumentsState()
    }
    fun activate(value: CommercialAuthority) {
        deactivate()
        authority = value.copy(permissions = value.permissions.toSet())
        val permissions = value.permissions
        mutableState.value = BusinessDocumentsState(
            canDownloadContent = "document.read" in permissions &&
                "document.download" in permissions
        )
        refresh()
    }
    fun refresh() = load(state.value.page)
    fun nextPage() {
        val s = state.value
        if (s.status == "Current" &&
            (s.page + 1) * 25L < (s.totalItems ?: 0)
        ) {
            load(s.page + 1)
        }
    }
    fun previousPage() {
        if (state.value.page > 0 &&
            state.value.status == "Current"
        ) {
            load(state.value.page - 1)
        }
    }
    private fun load(page: Int) {
        val canDownloadContent = state.value.canDownloadContent
        perform(BusinessDocumentsState(page = page, canDownloadContent = canDownloadContent)) {
            gateway.list(it, page)
        }
    }
    fun open(id: String) {
        val current = state.value
        if (current.status != "Current" || current.contentStatus == "Pending" ||
            current.items.none { it.id == id }
        ) {
            return
        }
        if (!current.canDownloadContent) {
            mutableState.value = current.copy(content = null, contentStatus = "DownloadDenied")
            return
        }
        perform(current.copy(content = null), contentRequest = true) { gateway.content(it, id) }
    }
    fun closeContent() {
        mutableState.value = state.value.copy(content = null, contentStatus = "NotRequested")
    }
    private fun perform(
        input: BusinessDocumentsState,
        contentRequest: Boolean = false,
        action: suspend (CommercialAuthority) -> BusinessDocumentsResult
    ) {
        val captured = authority ?: return
        val request = ++generation
        mutableState.value = if (contentRequest) {
            input.copy(content = null, contentStatus = "Pending")
        } else {
            input.copy(status = "Pending", content = null, contentStatus = "NotRequested")
        }
        viewModelScope.launch {
            val result = try {
                action(captured)
            } catch (
                cancelled: CancellationException
            ) {
                throw cancelled
            } catch (_: Exception) {
                BusinessDocumentsResult.Unavailable
            }
            if (authority != captured || generation != request) return@launch
            mutableState.value = when (result) {
                is BusinessDocumentsResult.Page -> input.copy(
                    items = result.items,
                    totalItems = result.totalItems,
                    status = "Current",
                    content = null,
                    contentStatus = "NotRequested"
                )

                is BusinessDocumentsResult.Content -> input.copy(
                    content = result.value,
                    status = "Current",
                    contentStatus = "Current"
                )

                BusinessDocumentsResult.Denied -> BusinessDocumentsState(
                    status = "PermissionDenied"
                )

                BusinessDocumentsResult.Unavailable -> input.copy(
                    status = if (contentRequest) input.status else "Unavailable",
                    content = null,
                    contentStatus = if (contentRequest) "Unavailable" else "NotRequested"
                )
            }
        }
    }
}
