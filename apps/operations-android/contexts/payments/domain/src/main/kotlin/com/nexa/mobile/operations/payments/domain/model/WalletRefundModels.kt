package com.nexa.mobile.operations.payments.domain.model

import java.math.BigDecimal

/** Immutable server projection; every amount retains the API's decimal scale. */
data class WalletRefundCandidate(
    val salesOrderId: String,
    val orderNumber: String,
    val currency: String,
    val consumedPEN: BigDecimal,
    val refundedPEN: BigDecimal,
    val refundablePEN: BigDecimal
)

data class WalletRefundCandidatePage(
    val items: List<WalletRefundCandidate>,
    val page: Int,
    val size: Int,
    val total: Long
)

/** Posted ledger facts returned by the API. */
data class WalletRefundPosted(
    val salesOrderId: String,
    val refundId: String,
    val ledgerEntryId: String,
    val amountPEN: BigDecimal,
    val currency: String,
    val status: String
)
