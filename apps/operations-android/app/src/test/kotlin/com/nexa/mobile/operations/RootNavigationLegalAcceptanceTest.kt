package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsSessionLease
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.legal.LegalAcceptanceUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootNavigationLegalAcceptanceTest {
    @Test
    fun onlyActiveSessionsAreGatedAndOnlyAfterServerConfirmedAcceptance() {
        assertTrue(
            requiresLegalAcceptance(
                SessionState.Active,
                LegalAcceptanceUiState.Loading,
                hasConfirmedContext = true,
                authorityEpoch = 4
            )
        )
        assertTrue(
            requiresLegalAcceptance(
                SessionState.Active,
                LegalAcceptanceUiState.Unavailable,
                hasConfirmedContext = true,
                authorityEpoch = 4
            )
        )
        assertFalse(
            requiresLegalAcceptance(
                SessionState.Active,
                LegalAcceptanceUiState.Accepted(MobileTermsSessionLease(1, 1), authorityEpoch = 4),
                hasConfirmedContext = true,
                authorityEpoch = 4
            )
        )
        assertTrue(
            requiresLegalAcceptance(
                SessionState.Active,
                LegalAcceptanceUiState.Accepted(MobileTermsSessionLease(1, 1), authorityEpoch = 4),
                hasConfirmedContext = true,
                authorityEpoch = 5
            )
        )
        assertFalse(
            requiresLegalAcceptance(
                SessionState.Active,
                LegalAcceptanceUiState.Loading,
                hasConfirmedContext = false,
                authorityEpoch = 4
            )
        )
        assertFalse(
            requiresLegalAcceptance(
                SessionState.ContextRequired,
                LegalAcceptanceUiState.Inactive,
                hasConfirmedContext = false,
                authorityEpoch = 4
            )
        )
        assertFalse(
            requiresLegalAcceptance(
                SessionState.SignedOut,
                LegalAcceptanceUiState.Inactive,
                hasConfirmedContext = false,
                authorityEpoch = 4
            )
        )
    }
}
