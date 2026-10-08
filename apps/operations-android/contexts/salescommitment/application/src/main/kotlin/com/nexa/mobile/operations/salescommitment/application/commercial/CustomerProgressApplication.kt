package com.nexa.mobile.operations.salescommitment.application.commercial

import com.nexa.mobile.operations.creditreceivables.domain.model.commercial.CustomerCredit
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.CustomerCommitment
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority

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
