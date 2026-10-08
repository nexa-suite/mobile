package com.nexa.mobile.operations.creditreceivables.application.publicapi

import com.nexa.mobile.operations.creditreceivables.domain.model.commercial.CustomerCredit

sealed interface CustomerCreditExposureRead {
    data class Available(val value: CustomerCredit) : CustomerCreditExposureRead
    data object PermissionDenied : CustomerCreditExposureRead
    data object Unavailable : CustomerCreditExposureRead
}

interface CustomerCreditExposureQuery {
    suspend fun credit(customerId: String, currency: String): CustomerCreditExposureRead
}
