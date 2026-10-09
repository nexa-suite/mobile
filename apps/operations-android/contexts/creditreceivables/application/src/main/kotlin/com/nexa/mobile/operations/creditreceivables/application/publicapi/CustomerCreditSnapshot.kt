package com.nexa.mobile.operations.creditreceivables.application.publicapi

/** Published read-only credit facts; the API owns all credit decisions. */
data class CustomerCreditSnapshot(
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
