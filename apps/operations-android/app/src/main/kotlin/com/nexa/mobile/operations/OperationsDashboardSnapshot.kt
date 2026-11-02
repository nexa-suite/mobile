package com.nexa.mobile.operations

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.customerbuyerrelationships.presentation.commercial.CustomerSearchState
import com.nexa.mobile.operations.customerbuyerrelationships.presentation.commercial.CustomerSearchStatus
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchReadinessStatus
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchReadinessUiState
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.warehouse.PickingWorkListStatus
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.warehouse.PickingWorkListUiState
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockConditionLot
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.StockConditionStatus
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.StockConditionUiState
import java.util.Locale

internal enum class OperationsDashboardRole {
    Warehouse,
    Dispatch,
    Management,
    Sales,
    Driver,
    Unknown
}

internal enum class OperationsDashboardMetricKey {
    Preparations,
    TotalLots,
    HeldLots,
    ExpiredLots,
    Ready,
    InRoute,
    Attention,
    Customers
}

internal enum class OperationsDashboardMetricStatus {
    Loading,
    Current,
    Unavailable
}

internal enum class OperationsDashboardAccent {
    Primary,
    Amber,
    Teal,
    Purple
}

@Immutable
internal data class OperationsDashboardMetric(
    val key: OperationsDashboardMetricKey,
    val labelResource: Int,
    val value: Long?,
    val status: OperationsDashboardMetricStatus,
    val accent: OperationsDashboardAccent
)

@Immutable
internal data class OperationsDashboardSnapshot(
    val authorityEpoch: Long = 0,
    val role: OperationsDashboardRole = OperationsDashboardRole.Unknown,
    val metrics: List<OperationsDashboardMetric> = emptyList()
)

internal fun operationsDashboardRole(roles: Set<String>): OperationsDashboardRole {
    val normalized = roles.map { it.trim().uppercase(Locale.ROOT) }.toSet()
    return when {
        normalized.any { it in setOf("WAREHOUSE", "WAREHOUSE_OPERATOR") } ->
            OperationsDashboardRole.Warehouse

        normalized.any { it in setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR") } ->
            OperationsDashboardRole.Dispatch

        normalized.any { it in setOf("SALES", "SALES_REPRESENTATIVE", "COMMERCIAL") } ->
            OperationsDashboardRole.Sales

        normalized.any { it in setOf("BOM", "BUSINESS_OPERATIONS_MANAGER") } ->
            OperationsDashboardRole.Management

        "DRIVER" in normalized -> OperationsDashboardRole.Driver

        else -> OperationsDashboardRole.Unknown
    }
}

internal fun buildOperationsDashboardSnapshot(
    authorityEpoch: Long,
    role: OperationsDashboardRole,
    openedEpoch: Long,
    openedRole: OperationsDashboardRole,
    scopeKey: String?,
    openedScopeKey: String?,
    stockState: StockConditionUiState,
    stockReadAllowed: Boolean,
    pickingState: PickingWorkListUiState,
    pickingEpoch: Long,
    pickingReadAllowed: Boolean,
    readinessState: DispatchReadinessUiState,
    dispatchReadAllowed: Boolean,
    customerState: CustomerSearchState,
    customerEpoch: Long,
    customerReadAllowed: Boolean
): OperationsDashboardSnapshot {
    val openedForCurrentAuthority = authorityEpoch > 0 &&
        openedEpoch == authorityEpoch &&
        openedRole == role &&
        !scopeKey.isNullOrBlank() &&
        openedScopeKey == scopeKey

    fun metric(
        key: OperationsDashboardMetricKey,
        label: Int,
        accent: OperationsDashboardAccent,
        allowed: Boolean,
        sourceEpochMatches: Boolean,
        loading: Boolean,
        current: Boolean,
        previousFacts: Boolean,
        value: Long?
    ): OperationsDashboardMetric {
        val status = when {
            !allowed -> OperationsDashboardMetricStatus.Unavailable
            !openedForCurrentAuthority -> OperationsDashboardMetricStatus.Loading
            !sourceEpochMatches -> OperationsDashboardMetricStatus.Loading
            current -> OperationsDashboardMetricStatus.Current
            loading -> OperationsDashboardMetricStatus.Loading
            else -> OperationsDashboardMetricStatus.Unavailable
        }
        val visibleValue = when (status) {
            OperationsDashboardMetricStatus.Current -> value

            OperationsDashboardMetricStatus.Loading -> value.takeIf {
                previousFacts && openedForCurrentAuthority && sourceEpochMatches
            }

            OperationsDashboardMetricStatus.Unavailable -> null
        }
        return OperationsDashboardMetric(key, label, visibleValue, status, accent)
    }

    fun stockMetric(
        key: OperationsDashboardMetricKey,
        label: Int,
        accent: OperationsDashboardAccent,
        value: (List<StockConditionLot>) -> Long
    ): OperationsDashboardMetric {
        val scopeMatches = stockState.authorityEpoch == authorityEpoch
        val hasFacts = stockState.listObservedAt != null
        val current = stockState.status == StockConditionStatus.Current ||
            stockState.status == StockConditionStatus.Empty
        return metric(
            key = key,
            label = label,
            accent = accent,
            allowed = stockReadAllowed,
            sourceEpochMatches = scopeMatches,
            loading = stockState.status == StockConditionStatus.Initial ||
                stockState.status == StockConditionStatus.Loading,
            current = current,
            previousFacts = hasFacts,
            value = value(stockState.lots)
        )
    }

    fun readinessMetric(
        key: OperationsDashboardMetricKey,
        label: Int,
        accent: OperationsDashboardAccent,
        count: (List<DispatchReadiness>) -> Long
    ): OperationsDashboardMetric {
        val scopeMatches = readinessState.authorityEpoch == authorityEpoch
        val hasFacts = readinessState.asOf != null
        val current = readinessState.status == DispatchReadinessStatus.Current ||
            readinessState.status == DispatchReadinessStatus.Empty
        return metric(
            key = key,
            label = label,
            accent = accent,
            allowed = dispatchReadAllowed,
            sourceEpochMatches = scopeMatches,
            loading = readinessState.status == DispatchReadinessStatus.Initial ||
                readinessState.status == DispatchReadinessStatus.Loading,
            current = current,
            previousFacts = hasFacts,
            value = count(readinessState.items)
        )
    }

    val metrics = when (role) {
        OperationsDashboardRole.Warehouse -> listOf(
            metric(
                OperationsDashboardMetricKey.Preparations,
                R.string.dashboard_preparations,
                OperationsDashboardAccent.Primary,
                pickingReadAllowed,
                pickingEpoch == authorityEpoch,
                pickingState.status == PickingWorkListStatus.NotRequested ||
                    pickingState.status == PickingWorkListStatus.Loading,
                pickingState.status == PickingWorkListStatus.Ready,
                previousFacts = false,
                value = pickingState.totalItems.coerceAtLeast(0)
            ),
            stockMetric(
                OperationsDashboardMetricKey.TotalLots,
                R.string.dashboard_total_lots,
                OperationsDashboardAccent.Teal
            ) { lots -> lots.size.toLong() },
            stockMetric(
                OperationsDashboardMetricKey.HeldLots,
                R.string.dashboard_held_lots,
                OperationsDashboardAccent.Amber
            ) { lots ->
                lots.count {
                    it.status.equals("HOLD", true) || it.status.equals("QUARANTINED", true)
                }
                    .toLong()
            },
            stockMetric(
                OperationsDashboardMetricKey.ExpiredLots,
                R.string.dashboard_expired_lots,
                OperationsDashboardAccent.Purple
            ) { lots ->
                lots.count { it.status.equals("EXPIRED", true) }.toLong()
            }
        )

        OperationsDashboardRole.Dispatch,
        OperationsDashboardRole.Management -> listOf(
            readinessMetric(
                OperationsDashboardMetricKey.Preparations,
                R.string.dashboard_preparations,
                OperationsDashboardAccent.Primary
            ) { items -> items.count { it.dashboardLane() == DashboardLane.Preparation }.toLong() },
            readinessMetric(
                OperationsDashboardMetricKey.Ready,
                R.string.dashboard_ready,
                OperationsDashboardAccent.Teal
            ) { items -> items.count { it.dashboardLane() == DashboardLane.Ready }.toLong() },
            readinessMetric(
                OperationsDashboardMetricKey.InRoute,
                R.string.dashboard_in_route,
                OperationsDashboardAccent.Purple
            ) { items -> items.count { it.dashboardLane() == DashboardLane.InRoute }.toLong() },
            readinessMetric(
                OperationsDashboardMetricKey.Attention,
                R.string.dashboard_attention,
                OperationsDashboardAccent.Amber
            ) { items ->
                items.count { it.dashboardLane() == DashboardLane.Attention }.toLong()
            }
        )

        OperationsDashboardRole.Sales -> listOf(
            metric(
                OperationsDashboardMetricKey.Customers,
                R.string.dashboard_customers,
                OperationsDashboardAccent.Primary,
                customerReadAllowed,
                customerEpoch == authorityEpoch,
                customerState.status == CustomerSearchStatus.Idle ||
                    customerState.status == CustomerSearchStatus.Loading,
                customerState.status == CustomerSearchStatus.Current &&
                    customerState.total != null,
                previousFacts = false,
                value = customerState.total?.coerceAtLeast(0)
            )
        )

        OperationsDashboardRole.Driver,
        OperationsDashboardRole.Unknown -> emptyList()
    }

    return OperationsDashboardSnapshot(
        authorityEpoch = authorityEpoch,
        role = role,
        metrics = when (role) {
            OperationsDashboardRole.Warehouse -> metrics.filter {
                if (it.key == OperationsDashboardMetricKey.Preparations) {
                    pickingReadAllowed
                } else {
                    stockReadAllowed
                }
            }

            OperationsDashboardRole.Dispatch,
            OperationsDashboardRole.Management -> if (dispatchReadAllowed) metrics else emptyList()

            OperationsDashboardRole.Sales -> if (customerReadAllowed) metrics else emptyList()

            OperationsDashboardRole.Driver,
            OperationsDashboardRole.Unknown -> emptyList()
        }
    )
}

private enum class DashboardLane {
    Preparation,
    Ready,
    InRoute,
    Attention,
    Finished
}

private fun DispatchReadiness.dashboardLane(): DashboardLane {
    when (deliveryStatus?.uppercase(Locale.ROOT)) {
        "DELIVERED" -> return DashboardLane.Finished
        "DISPATCHED", "IN_TRANSIT" -> return DashboardLane.InRoute
        "PARTIAL", "FAILED", "CANCELLED", "PLANNED" -> return DashboardLane.Attention
        null, "ASSIGNED" -> Unit
        else -> return DashboardLane.Attention
    }

    val status = fulfillmentStatus.uppercase(Locale.ROOT)
    return when {
        ready && status == "READY_FOR_DISPATCH" -> DashboardLane.Ready

        !ready && (status == "ALLOCATED" || status == "PICKING") ->
            DashboardLane.Preparation

        else -> DashboardLane.Attention
    }
}
