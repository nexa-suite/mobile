package com.nexa.mobile.operations.creditreceivables.application.publicapi

sealed interface CustomerCreditExposureRead {
    data class Available(val value: CustomerCreditSnapshot) : CustomerCreditExposureRead
    data object PermissionDenied : CustomerCreditExposureRead
    data object Unavailable : CustomerCreditExposureRead
}

interface CustomerCreditExposureQuery {
    suspend fun credit(customerId: String, currency: String): CustomerCreditExposureRead
}
