package com.nexa.mobile.operations.salescommitment.application.commercial

import com.nexa.mobile.operations.creditreceivables.application.publicapi.CustomerCreditSnapshot
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.CustomerCommitment
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority

data class CustomerProgressResult(
    val customerName: String?,
    val commitments: List<CustomerCommitment>,
    val credit: CustomerCreditSnapshot?,
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
