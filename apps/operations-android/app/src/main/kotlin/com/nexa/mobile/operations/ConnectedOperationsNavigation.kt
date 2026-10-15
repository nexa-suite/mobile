package com.nexa.mobile.operations

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseUiState
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.designsystem.NexaSearchField
import com.nexa.mobile.operations.core.designsystem.NexaTaskRow
import com.nexa.mobile.operations.core.designsystem.NexaWorkSummary
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessStage
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessUiState

/** Reachable capability entries, including explicit unavailable states, use current authority. */
internal data class ConnectedOperationEntry(
    val key: String,
    val label: String,
    val readPermissions: Set<String>,
    val visibleInHub: Boolean = true,
    val readRoles: Set<String> = emptySet(),
    val requiredReadPermissionGroups: List<Set<String>> = emptyList()
) {
    init {
        require(key.isNotBlank() && label.isNotBlank() && readPermissions.isNotEmpty())
        require(requiredReadPermissionGroups.all { it.isNotEmpty() })
    }
}

/** In-memory route, invalidated by authority replacement; never persisted as authority. */
internal data class ConnectedOperationRoute(
    val entryKey: String,
    val authorityEpoch: Long,
    val authority: VerifiedContextAuthority
) {
    override fun toString(): String = "ConnectedOperationRoute(entry=$entryKey, authority=REDACTED)"
}

internal object ConnectedOperationsNavigation {
    fun currentAuthority(
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState
    ): VerifiedContextAuthority? {
        if (session != SessionState.Active || access.stage != AccessStage.WorkAuthorized ||
            access.authorityEpoch <= 0 || warehouse.authorityEpoch != access.authorityEpoch
        ) {
            return null
        }
        val context = access.activeContext?.takeIf { it.isCurrent } ?: return null
        val authority = context.verifiedAuthority ?: return null
        if (listOf(
                authority.userId,
                authority.tenantId,
                authority.workspaceId,
                authority.membershipId
            )
                .any(String::isBlank)
        ) {
            return null
        }
        val operationalContext = warehouse.activeContext ?: return null
        if (operationalContext.authorityEpoch != access.authorityEpoch) return null
        val identity = operationalContext.verifiedIdentity ?: return null
        if (identity.userId != authority.userId || identity.tenantId != authority.tenantId ||
            identity.workspaceId != authority.workspaceId ||
            identity.membershipId != authority.membershipId ||
            identity.permissions != authority.permissions ||
            identity.roles != authority.roles
        ) {
            return null
        }
        return authority
    }

    fun visibleEntries(
        entries: List<ConnectedOperationEntry>,
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState
    ): List<ConnectedOperationEntry> {
        val authority = currentAuthority(session, access, warehouse) ?: return emptyList()
        return uniqueEntries(entries).filter { entry ->
            entry.visibleInHub &&
                permits(authority, entry)
        }
    }

    fun open(
        entry: ConnectedOperationEntry,
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState
    ): ConnectedOperationRoute? {
        val authority = currentAuthority(session, access, warehouse) ?: return null
        if (!permits(authority, entry)) return null
        return ConnectedOperationRoute(
            entry.key,
            access.authorityEpoch,
            authority.copy(
                permissions = authority.permissions.toSet(),
                roles = authority.roles.toSet()
            )
        )
    }

    fun permits(
        route: ConnectedOperationRoute,
        entries: List<ConnectedOperationEntry>,
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState
    ): Boolean {
        val entry = uniqueEntries(entries).singleOrNull { it.key == route.entryKey } ?: return false
        if (route.authorityEpoch != access.authorityEpoch) return false
        val current = currentAuthority(session, access, warehouse) ?: return false
        return current == route.authority &&
            permits(current, entry)
    }

    private fun permits(authority: VerifiedContextAuthority, entry: ConnectedOperationEntry) =
        authority.permissions.any(entry.readPermissions::contains) &&
            entry.requiredReadPermissionGroups.all { group ->
                authority.permissions.any(group::contains)
            } &&
            (entry.readRoles.isEmpty() || authority.roles.any(entry.readRoles::contains))

    /** Identical declarations collapse; conflicting declarations for a key fail closed. */
    private fun uniqueEntries(
        entries: List<ConnectedOperationEntry>
    ): List<ConnectedOperationEntry> =
        entries.groupBy(ConnectedOperationEntry::key).values.mapNotNull { sameKey ->
            sameKey.distinct().singleOrNull()
        }
}

@Composable
internal fun ConnectedOperationsEntries(
    entries: List<ConnectedOperationEntry>,
    roles: Set<String>,
    onOpen: (ConnectedOperationEntry) -> Unit
) {
    if (entries.isEmpty()) return
    val groups = entries.groupBy { it.workGroup() }
    val available = WorkGroup.entries.filter { groups[it].orEmpty().isNotEmpty() }
    val preferred = when {
        "DRIVER" in roles -> WorkGroup.Driver
        "WAREHOUSE" in roles -> WorkGroup.Warehouse
        "LOGISTICS" in roles -> WorkGroup.Dispatch
        else -> available.first()
    }
    var selected by remember(available, preferred) {
        mutableStateOf(preferred.takeIf { it in available } ?: available.first())
    }
    var query by remember(selected) { mutableStateOf("") }
    var showTools by remember(selected) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NexaWorkSummary(
            title = stringResource(R.string.work_hub_title),
            description = stringResource(R.string.work_hub_support)
        )
        if (available.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                available.forEach { group ->
                    FilterChip(
                        selected = selected == group,
                        onClick = { selected = group },
                        label = { Text(stringResource(group.titleResource)) }
                    )
                }
            }
        }
        Text(
            stringResource(selected.titleResource),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        val groupEntries = groups[selected].orEmpty()
        if (groupEntries.size > 4) {
            NexaSearchField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.work_hub_search),
                hint = stringResource(R.string.work_hub_search_hint)
            )
        }
        val matching = groupEntries.filter { it.label.contains(query.trim(), ignoreCase = true) }
        val primaryKeys = setOf(
            "warehouse.batch",
            "dispatch.readiness",
            "dispatch.loads",
            "driver.loads",
            "driver.deliveries",
            "commercial.customers",
            "commercial.request",
            "commercial.direct-order",
            "operations.notifications"
        )
        val primary = matching.filter { it.key in primaryKeys }
        val supporting = matching.filterNot { it.key in primaryKeys }
        if (matching.isEmpty()) {
            Text(
                stringResource(R.string.work_hub_no_results),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        primary.forEach { entry ->
            NexaTaskRow(
                title = entry.label,
                description = stringResource(entry.descriptionResource()),
                onClick = { onOpen(entry) }
            )
        }
        if (supporting.isNotEmpty()) {
            TextButton(onClick = { showTools = !showTools }) {
                Text(
                    stringResource(
                        if (showTools) {
                            R.string.work_hub_hide_supporting
                        } else {
                            R.string.work_hub_supporting
                        },
                        supporting.size
                    )
                )
            }
            if (showTools || query.isNotBlank()) {
                supporting.forEach { entry ->
                    NexaTaskRow(
                        title = entry.label,
                        description = stringResource(entry.descriptionResource()),
                        onClick = { onOpen(entry) }
                    )
                }
            }
        }
    }
}

private enum class WorkGroup(val titleResource: Int) {
    Warehouse(R.string.work_hub_warehouse),
    Dispatch(R.string.work_hub_dispatch),
    Driver(R.string.work_hub_driver),
    Commercial(R.string.work_hub_commercial),
    Other(R.string.work_hub_other)
}

private fun ConnectedOperationEntry.workGroup(): WorkGroup = when {
    key.startsWith("warehouse.") -> WorkGroup.Warehouse

    key.startsWith("dispatch.") || key == "operations.overview" ||
        key == "operations.exceptions" || key == "bom.exceptions" -> WorkGroup.Dispatch

    key.startsWith("driver.") -> WorkGroup.Driver

    key.startsWith("commercial.") || key.startsWith("payments.") -> WorkGroup.Commercial

    else -> WorkGroup.Other
}

private fun ConnectedOperationEntry.descriptionResource(): Int = when (key) {
    "warehouse.batch" -> R.string.work_hub_picking_support
    "dispatch.readiness" -> R.string.work_hub_readiness_support
    "dispatch.loads" -> R.string.work_hub_loads_support
    "driver.loads" -> R.string.work_hub_accept_load_support
    "driver.deliveries" -> R.string.work_hub_deliveries_support
    "operations.overview" -> R.string.work_hub_overview_support
    "operations.exceptions", "bom.exceptions" -> R.string.work_hub_exceptions_support
    "commercial.customers" -> R.string.work_hub_customers_support
    "commercial.request" -> R.string.work_hub_request_support
    "commercial.direct-order" -> R.string.work_hub_direct_order_support
    "warehouse.cycle-count" -> R.string.work_hub_count_support
    "warehouse.transfer", "warehouse.transfer-receipt" -> R.string.work_hub_transfer_support
    "warehouse.temperature", "warehouse.disposition" -> R.string.work_hub_condition_support
    "commercial.documents" -> R.string.work_hub_documents_support
    "operations.notifications" -> R.string.work_hub_notifications_support
    else -> R.string.connected_operation_open_support
}
