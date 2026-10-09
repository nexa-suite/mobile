package com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse

import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsContext
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.ActiveOperationsContext

/** Translates the selected workforce scope into Catalog's own client projection. */
fun interface CatalogOperationsContextProjector {
    fun project(context: ActiveOperationsContext): CatalogOperationsContext
}
