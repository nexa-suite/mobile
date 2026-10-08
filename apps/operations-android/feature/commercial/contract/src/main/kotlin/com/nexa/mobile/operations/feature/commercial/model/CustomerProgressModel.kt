package com.nexa.mobile.operations.feature.commercial.model

data class CustomerCommitment(
    val id: String,
    val number: String,
    val status: String,
    val total: String,
    val currency: String,
    val version: Long,
    val updatedAt: String
)

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

enum class ProgressStatus { Idle, Pending, Current, Unavailable, PermissionDenied }
