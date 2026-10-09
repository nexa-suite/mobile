package com.nexa.mobile.operations.creditreceivables.domain.model.commercial

data class CustomerCredit(
    val currency: String,
    val limit: String,
    val ledgerExposure: String,
    val outstanding: String,
    val reserved: String,
    val used: String,
    val available: String,
    val active: Boolean,
    val asOf: String
)
