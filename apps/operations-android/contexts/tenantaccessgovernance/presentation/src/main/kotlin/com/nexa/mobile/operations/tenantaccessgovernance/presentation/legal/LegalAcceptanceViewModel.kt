package com.nexa.mobile.operations.tenantaccessgovernance.presentation.legal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.LegalAcceptanceGateway
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsAcceptResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsAcceptanceResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsDocument
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsDocumentResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LegalAcceptanceUiState {
    data object Inactive : LegalAcceptanceUiState
    data object Loading : LegalAcceptanceUiState
    data object Unavailable : LegalAcceptanceUiState
    data object SessionExpired : LegalAcceptanceUiState
    data class Accepted(val lease: MobileTermsSessionLease, val authorityEpoch: Long) :
        LegalAcceptanceUiState
    data class RequiresAcceptance(
        val document: MobileTermsDocument,
        val error: LegalAcceptanceError? = null,
        val submitting: Boolean = false
    ) : LegalAcceptanceUiState
}

enum class LegalAcceptanceError {
    StaleDocument,
    UnknownOutcome,
    Unavailable
}

sealed interface PublicTermsUiState {
    data object Idle : PublicTermsUiState
    data object Loading : PublicTermsUiState
    data object Unavailable : PublicTermsUiState
    data class Available(val document: MobileTermsDocument) : PublicTermsUiState
}

class LegalAcceptanceViewModel(private val gateway: LegalAcceptanceGateway) : ViewModel() {
    private val mutableState =
        MutableStateFlow<LegalAcceptanceUiState>(LegalAcceptanceUiState.Inactive)
    val state = mutableState.asStateFlow()

    private val mutablePublicTerms = MutableStateFlow<PublicTermsUiState>(PublicTermsUiState.Idle)
    val publicTerms = mutablePublicTerms.asStateFlow()

    private var generation = 0L
    private var publicTermsGeneration = 0L
    private var activeAuthorityEpoch: Long? = null
    private var lastAcceptedLease: MobileTermsSessionLease? = null

    fun activate(authorityEpoch: Long) {
        if (activeAuthorityEpoch == authorityEpoch &&
            mutableState.value != LegalAcceptanceUiState.Inactive
        ) {
            return
        }
        lastAcceptedLease = null
        activeAuthorityEpoch = authorityEpoch
        refreshAcceptance()
    }

    fun deactivate() {
        generation++
        activeAuthorityEpoch = null
        lastAcceptedLease = null
        mutableState.value = LegalAcceptanceUiState.Inactive
    }

    fun loadPublicTerms() {
        if (mutablePublicTerms.value == PublicTermsUiState.Loading) return
        val request = ++publicTermsGeneration
        mutablePublicTerms.value = PublicTermsUiState.Loading
        viewModelScope.launch {
            val result = try {
                gateway.loadPublicTerms()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                MobileTermsDocumentResult.Unavailable
            }
            if (request != publicTermsGeneration) return@launch
            mutablePublicTerms.value = when (result) {
                is MobileTermsDocumentResult.Available ->
                    PublicTermsUiState.Available(result.document)

                MobileTermsDocumentResult.Unavailable -> PublicTermsUiState.Unavailable
            }
        }
    }

    fun retryPublicTerms() {
        mutablePublicTerms.value = PublicTermsUiState.Idle
        loadPublicTerms()
    }

    fun accept(document: MobileTermsDocument, confirmed: Boolean) {
        if (!confirmed) return
        val current = mutableState.value as? LegalAcceptanceUiState.RequiresAcceptance ?: return
        if (current.document != document || current.submitting) return
        val request = ++generation
        mutableState.value = current.copy(submitting = true, error = null)
        viewModelScope.launch {
            val result = try {
                gateway.accept(document)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                MobileTermsAcceptResult.UnknownOutcome
            }
            if (request != generation) return@launch
            when (result) {
                MobileTermsAcceptResult.SessionExpired ->
                    mutableState.value = LegalAcceptanceUiState.SessionExpired

                MobileTermsAcceptResult.StaleDocument -> refreshAcceptance(
                    stale = true,
                    requestGeneration = request
                )

                MobileTermsAcceptResult.Accepted -> refreshAcceptance(
                    requestGeneration = request
                )

                MobileTermsAcceptResult.UnknownOutcome -> refreshAcceptance(
                    error = LegalAcceptanceError.UnknownOutcome,
                    requestGeneration = request
                )

                MobileTermsAcceptResult.Unavailable -> mutableState.value = current.copy(
                    submitting = false,
                    error = LegalAcceptanceError.Unavailable
                )
            }
        }
    }

    fun retryAcceptance() = refreshAcceptance()

    private fun refreshAcceptance(
        error: LegalAcceptanceError? = null,
        stale: Boolean = false,
        requestGeneration: Long = ++generation
    ) {
        val authorityEpoch = activeAuthorityEpoch ?: return
        mutableState.value = LegalAcceptanceUiState.Loading
        viewModelScope.launch {
            val result = try {
                gateway.loadAcceptance()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                MobileTermsAcceptanceResult.SessionExpired
            }
            if (requestGeneration != generation) return@launch
            mutableState.value = when (result) {
                is MobileTermsAcceptanceResult.Available -> {
                    val acceptance = result.acceptance
                    if (acceptance.accepted) {
                        lastAcceptedLease = result.lease
                        LegalAcceptanceUiState.Accepted(result.lease, authorityEpoch)
                    } else {
                        lastAcceptedLease = null
                        LegalAcceptanceUiState.RequiresAcceptance(
                            acceptance.document,
                            error = if (stale) LegalAcceptanceError.StaleDocument else error
                        )
                    }
                }

                MobileTermsAcceptanceResult.SessionExpired -> {
                    lastAcceptedLease = null
                    LegalAcceptanceUiState.SessionExpired
                }

                is MobileTermsAcceptanceResult.Unavailable -> {
                    if (result.transportFailure && lastAcceptedLease == result.lease) {
                        LegalAcceptanceUiState.Accepted(result.lease, authorityEpoch)
                    } else {
                        lastAcceptedLease = null
                        LegalAcceptanceUiState.Unavailable
                    }
                }
            }
        }
    }
}
