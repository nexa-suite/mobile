package com.nexa.mobile.operations.salescommitment.presentation.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestCustomerRead
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestDraftGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestDraftRead
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestProductRead
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestDraftStore
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestDraft
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestCustomerFacts
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestDraftLine
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestProductFacts
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FieldRequestDraftViewModel(
    private val store: FieldRequestDraftStore,
    private val gateway: FieldRequestDraftGateway
) : ViewModel() {
    private val mutableState = MutableStateFlow(FieldRequestDraftState())
    val state = mutableState.asStateFlow()
    private var authority: CommercialAuthority? = null
    private var generation = 0L
    private val mutex = Mutex()

    fun activate(
        value: CommercialAuthority,
        requestedCustomerId: String? = null,
        requestedProductId: String? = null
    ) {
        deactivate()
        val captured = value.copy(permissions = value.permissions.toSet())
        authority = captured
        val epoch = generation
        viewModelScope.launch {
            mutex.withLock {
                val read = store.load(captured)
                if (!current(captured, epoch)) return@withLock
                val stored = when (read) {
                    is FieldRequestDraftRead.Available -> read.draft
                    FieldRequestDraftRead.Unavailable -> {
                        mutableState.value = FieldRequestDraftState(
                            status = FieldRequestDraftStatus.MetadataUnavailable
                        )
                        return@withLock
                    }
                }
                val draft = when {
                    requestedCustomerId.isNullOrBlank() -> stored
                    stored.customerId.isBlank() -> stored.copy(customerId = requestedCustomerId)
                    stored.customerId == requestedCustomerId -> stored
                    else -> FieldRequestDraft(customerId = requestedCustomerId)
                }
                if (draft != stored && !store.save(captured, draft)) {
                    mutableState.value = FieldRequestDraftState(
                        draft = draft,
                        status = FieldRequestDraftStatus.MetadataUnavailable
                    )
                    return@withLock
                }
                if (!current(captured, epoch)) return@withLock
                mutableState.value = FieldRequestDraftState(
                    draft = draft,
                    status = FieldRequestDraftStatus.Saved
                )
            }
            requestedProductId?.takeIf(String::isNotBlank)?.let(::addProduct)
        }
    }

    fun customerChanged(value: String) {
        val normalized = value.trim().take(200)
        editDraft { draft ->
            if (draft.customerId == normalized) draft
            else FieldRequestDraft(customerId = normalized)
        }
    }

    fun addProduct(catalogItemId: String) {
        val captured = authority ?: return
        val epoch = generation
        viewModelScope.launch {
            mutex.withLock {
                if (!current(captured, epoch)) return@withLock
                val draft = state.value.draft
                if (draft.customerId.isBlank()) {
                    mutableState.value = state.value.copy(
                        status = FieldRequestDraftStatus.ProductUnavailable
                    )
                    return@withLock
                }
                mutableState.value = state.value.copy(
                    status = FieldRequestDraftStatus.AddingProduct,
                    currentProducts = emptyMap(),
                    changedProducts = emptySet()
                )
                val result = try {
                    gateway.product(captured, draft.customerId, catalogItemId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    FieldRequestProductRead.Unavailable
                }
                if (!current(captured, epoch) ||
                    state.value.draft.customerId != draft.customerId
                ) {
                    return@withLock
                }
                val product = (result as? FieldRequestProductRead.Current)?.facts
                if (product == null) {
                    mutableState.value = state.value.copy(
                        status = FieldRequestDraftStatus.ProductUnavailable
                    )
                    return@withLock
                }
                val latest = state.value.draft
                val line = product.toDraftLine(
                    latest.lines.firstOrNull { it.catalogItemId == product.catalogItemId }
                        ?.quantity ?: "1"
                )
                val updated = latest.copy(
                    lines = latest.lines.filterNot { it.catalogItemId == line.catalogItemId } + line
                )
                mutableState.value = state.value.copy(
                    draft = updated,
                    status = FieldRequestDraftStatus.Saving
                )
                persist(captured, epoch, updated)
            }
        }
    }

    fun quantityChanged(catalogItemId: String, value: String) {
        val quantity = value.take(32)
        editDraft { draft ->
            draft.copy(lines = draft.lines.map { line ->
                if (line.catalogItemId == catalogItemId) line.copy(quantity = quantity) else line
            })
        }
    }

    fun removeProduct(catalogItemId: String) {
        editDraft { draft ->
            draft.copy(lines = draft.lines.filterNot { it.catalogItemId == catalogItemId })
        }
    }

    fun review() {
        val captured = authority ?: return
        val epoch = generation
        val draft = state.value.draft
        if (draft.customerId.isBlank()) return
        mutableState.value = state.value.copy(
            status = FieldRequestDraftStatus.Reviewing,
            currentProducts = emptyMap(),
            changedProducts = emptySet(),
            currentCustomer = null,
            customerChanged = false
        )
        viewModelScope.launch {
            val customer = try {
                gateway.customer(captured, draft.customerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                FieldRequestCustomerRead.Unavailable
            }
            val products = LinkedHashMap<String, FieldRequestProductFacts>()
            var allProductsCurrent = true
            for (line in draft.lines) {
                val result = try {
                    gateway.product(captured, draft.customerId, line.catalogItemId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    FieldRequestProductRead.Unavailable
                }
                val current = (result as? FieldRequestProductRead.Current)?.facts
                if (current == null) allProductsCurrent = false
                else products[line.catalogItemId] = current
            }
            mutex.withLock {
                if (!current(captured, epoch) || state.value.draft != draft) return@withLock
                val customerFacts = (customer as? FieldRequestCustomerRead.Current)?.facts
                    ?.takeIf { it.status == "ACTIVE" }
                val changedCustomer = customerFacts != null &&
                    draft.customerName != null &&
                    (draft.customerName != customerFacts.businessName ||
                        draft.customerVersion != customerFacts.version)
                val changedProducts = draft.lines.mapNotNull { line ->
                    val current = products[line.catalogItemId] ?: return@mapNotNull null
                    line.catalogItemId.takeIf { !line.matches(current) }
                }.toSet()
                val baseline = if (customerFacts != null && draft.customerName == null) {
                    draft.copy(
                        customerName = customerFacts.businessName,
                        customerVersion = customerFacts.version
                    )
                } else {
                    draft
                }
                val savedBaseline = baseline == draft || store.save(captured, baseline)
                if (!savedBaseline) {
                    mutableState.value = state.value.copy(
                        status = FieldRequestDraftStatus.MetadataUnavailable,
                        currentCustomer = customerFacts,
                        currentProducts = products,
                        changedProducts = changedProducts,
                        customerChanged = changedCustomer
                    )
                    return@withLock
                }
                mutableState.value = FieldRequestDraftState(
                    draft = baseline,
                    status = if (customerFacts != null && allProductsCurrent) {
                        FieldRequestDraftStatus.Reviewed
                    } else {
                        FieldRequestDraftStatus.ReviewUnavailable
                    },
                    currentProducts = products,
                    changedProducts = changedProducts,
                    currentCustomer = customerFacts,
                    customerChanged = changedCustomer
                )
            }
        }
    }

    private fun editDraft(change: (FieldRequestDraft) -> FieldRequestDraft) {
        val captured = authority ?: return
        val epoch = generation
        val changed = change(state.value.draft)
        if (changed == state.value.draft) return
        mutableState.value = state.value.copy(
            draft = changed,
            status = FieldRequestDraftStatus.Saving,
            currentProducts = emptyMap(),
            changedProducts = emptySet(),
            currentCustomer = null,
            customerChanged = false
        )
        viewModelScope.launch {
            mutex.withLock { persist(captured, epoch, state.value.draft) }
        }
    }

    private suspend fun persist(
        captured: CommercialAuthority,
        epoch: Long,
        draft: FieldRequestDraft
    ) {
        if (!current(captured, epoch)) return
        val saved = store.save(captured, draft)
        if (!current(captured, epoch) || state.value.draft != draft) return
        mutableState.value = state.value.copy(
            status = if (saved) {
                FieldRequestDraftStatus.Saved
            } else {
                FieldRequestDraftStatus.MetadataUnavailable
            }
        )
    }

    private fun FieldRequestProductFacts.toDraftLine(quantity: String) = FieldRequestDraftLine(
        catalogItemId = catalogItemId,
        productId = productId,
        name = name,
        skuCode = skuCode,
        brandName = brandName,
        categoryName = categoryName,
        familyName = familyName,
        variantName = variantName,
        unit = unit,
        quantity = quantity,
        price = price,
        currency = currency,
        pricingAsOf = pricingAsOf,
        availability = availability,
        availabilityStatus = availabilityStatus,
        availabilityAsOf = availabilityAsOf
    )

    private fun FieldRequestDraftLine.matches(current: FieldRequestProductFacts): Boolean =
        name == current.name && skuCode == current.skuCode && brandName == current.brandName &&
            categoryName == current.categoryName && familyName == current.familyName &&
            variantName == current.variantName && unit == current.unit && price == current.price &&
            currency == current.currency && pricingAsOf == current.pricingAsOf &&
            availability == current.availability &&
            availabilityStatus == current.availabilityStatus &&
            availabilityAsOf == current.availabilityAsOf

    private fun current(captured: CommercialAuthority, epoch: Long) =
        generation == epoch && authority == captured

    fun deactivate() {
        generation++
        authority = null
        mutableState.value = FieldRequestDraftState()
    }
}
