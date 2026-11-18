package com.nexa.mobile.operations

import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsSessionLease
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.legal.LegalAcceptanceUiState

internal val serverConfirmedLegalAcceptance =
    LegalAcceptanceUiState.Accepted(
        MobileTermsSessionLease(epoch = 1, generation = 1),
        authorityEpoch = 4
    )
