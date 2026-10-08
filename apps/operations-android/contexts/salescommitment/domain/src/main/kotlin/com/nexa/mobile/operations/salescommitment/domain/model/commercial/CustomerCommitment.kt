package com.nexa.mobile.operations.salescommitment.domain.model.commercial

data class CustomerCommitment(
    val id: String,
    val number: String,
    val status: String,
    val total: String,
    val currency: String,
    val version: Long,
    val updatedAt: String
)
