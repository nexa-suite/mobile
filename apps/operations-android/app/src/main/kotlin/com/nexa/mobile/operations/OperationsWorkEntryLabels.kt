package com.nexa.mobile.operations

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WorkEntryCapability
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WorkEntryCapabilityLabel
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R as FulfillmentR
import com.nexa.mobile.operations.inventoryavailability.presentation.R as InventoryR

/** Supplies entry copy for capabilities whose presentation is owned by another context. */
@Composable
internal fun operationsWorkEntryAdditionalCapabilityLabels():
    Map<WorkEntryCapability, WorkEntryCapabilityLabel> =
    mapOf(
        WorkEntryCapability.Picking to WorkEntryCapabilityLabel(
            title = stringResource(FulfillmentR.string.picking_title),
            description = stringResource(FulfillmentR.string.picking_entry_support)
        ),
        WorkEntryCapability.StockCondition to WorkEntryCapabilityLabel(
            title = stringResource(InventoryR.string.stock_condition_title),
            description = stringResource(InventoryR.string.stock_condition_disclaimer)
        ),
        WorkEntryCapability.Receiving to WorkEntryCapabilityLabel(
            title = stringResource(InventoryR.string.receiving_title),
            description = stringResource(InventoryR.string.receiving_choose_product)
        )
    )
