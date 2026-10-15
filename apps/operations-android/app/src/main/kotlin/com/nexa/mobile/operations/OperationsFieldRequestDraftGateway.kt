package com.nexa.mobile.operations

import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogResult
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters.OperationsCommercialCatalogGateway
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerQuery
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerRead
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestCustomerRead
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestDraftGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestProductRead
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestCustomerFacts
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestProductFacts
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.canReadCustomers
import javax.inject.Inject

/** Composition adapter keeps BC-03 and BC-02 transports outside BC-04 presentation. */
class OperationsFieldRequestDraftGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val catalog: OperationsCommercialCatalogGateway,
    private val customers: CommercialCustomerQuery
) : FieldRequestDraftGateway {
    override suspend fun product(
        authority: CommercialAuthority,
        customerId: String,
        catalogItemId: String
    ): FieldRequestProductRead {
        if (!current(authority)) return FieldRequestProductRead.PermissionDenied
        val result = catalog.detail(authority, customerId, catalogItemId)
        if (!current(authority)) return FieldRequestProductRead.PermissionDenied
        return when (result) {
            is CommercialCatalogResult.Product -> result.value.let { item ->
                FieldRequestProductRead.Current(
                    FieldRequestProductFacts(
                        item.catalogItemId,
                        item.productId,
                        item.name,
                        item.skuCode,
                        item.brandName,
                        item.categoryName,
                        item.familyName,
                        item.variantName,
                        item.unit,
                        item.price,
                        item.currency,
                        item.pricingAsOf,
                        item.sellableAvailability,
                        item.availabilityStatus,
                        item.availabilityAsOf
                    )
                )
            }

            CommercialCatalogResult.PermissionDenied -> FieldRequestProductRead.PermissionDenied

            CommercialCatalogResult.Unavailable -> FieldRequestProductRead.Unavailable

            is CommercialCatalogResult.Choices -> FieldRequestProductRead.Unavailable
        }
    }

    override suspend fun customer(
        authority: CommercialAuthority,
        customerId: String
    ): FieldRequestCustomerRead {
        if (!current(authority)) return FieldRequestCustomerRead.PermissionDenied
        val result = customers.detail(customerId)
        if (!current(authority)) return FieldRequestCustomerRead.PermissionDenied
        return when (result) {
            is CommercialCustomerRead.Detail -> result.value.let { customer ->
                FieldRequestCustomerRead.Current(
                    FieldRequestCustomerFacts(
                        customer.businessName,
                        customer.status,
                        customer.version
                    )
                )
            }

            CommercialCustomerRead.ContextInvalidated,
            CommercialCustomerRead.Unavailable -> FieldRequestCustomerRead.Unavailable
        }
    }

    private fun current(authority: CommercialAuthority): Boolean {
        val verified = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active && authority.canReadCustomers() &&
            verified.hasAuthorizedContext && verified.userId == authority.userId &&
            verified.tenantId == authority.tenantId &&
            verified.workspaceId == authority.workspaceId &&
            verified.membershipId == authority.membershipId &&
            verified.permissions == authority.permissions
    }
}
