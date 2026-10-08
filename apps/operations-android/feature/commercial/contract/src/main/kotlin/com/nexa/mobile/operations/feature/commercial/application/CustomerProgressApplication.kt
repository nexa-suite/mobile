package com.nexa.mobile.operations.feature.commercial.application

import com.nexa.mobile.operations.feature.commercial.model.CommercialAuthority
import com.nexa.mobile.operations.feature.commercial.model.CustomerCommitment
import com.nexa.mobile.operations.feature.commercial.model.CustomerCredit
import com.nexa.mobile.operations.feature.commercial.model.ProgressStatus

data class CustomerProgressResult(
    val customerName: String?,
    val commitments: List<CustomerCommitment>,
    val credit: CustomerCredit?,
    val commitmentsStatus: ProgressStatus,
    val creditStatus: ProgressStatus,
    val page: Int,
    val total: Long?
)

interface CustomerProgressGateway {
    suspend fun read(
        authority: CommercialAuthority,
        id: String,
        currency: String,
        page: Int
    ): CustomerProgressResult
}
