package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogCandidate
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogFilterKind
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogFilterPage
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogOutcome
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogPage
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingProductReference

/** Online catalog projection used to identify a receiving SKU; selection never creates stock. */
interface ReceivingProductCatalogGateway {
    suspend fun search(
        query: String,
        brandName: String?,
        categoryName: String?,
        pageKey: String?,
        authority: ReceivingAuthority
    ): ReceivingCatalogOutcome<ReceivingCatalogPage>

    suspend fun filterOptions(
        kind: ReceivingCatalogFilterKind,
        search: String,
        page: Int,
        authority: ReceivingAuthority
    ): ReceivingCatalogOutcome<ReceivingCatalogFilterPage>

    suspend fun detail(
        candidate: ReceivingCatalogCandidate,
        authority: ReceivingAuthority
    ): ReceivingCatalogOutcome<ReceivingProductReference>
}

object UnavailableReceivingProductCatalogGateway : ReceivingProductCatalogGateway {
    override suspend fun search(
        query: String,
        brandName: String?,
        categoryName: String?,
        pageKey: String?,
        authority: ReceivingAuthority
    ): ReceivingCatalogOutcome<ReceivingCatalogPage> = ReceivingCatalogOutcome.ServiceUnavailable

    override suspend fun filterOptions(
        kind: ReceivingCatalogFilterKind,
        search: String,
        page: Int,
        authority: ReceivingAuthority
    ): ReceivingCatalogOutcome<ReceivingCatalogFilterPage> =
        ReceivingCatalogOutcome.ServiceUnavailable

    override suspend fun detail(
        candidate: ReceivingCatalogCandidate,
        authority: ReceivingAuthority
    ): ReceivingCatalogOutcome<ReceivingProductReference> =
        ReceivingCatalogOutcome.ServiceUnavailable
}
