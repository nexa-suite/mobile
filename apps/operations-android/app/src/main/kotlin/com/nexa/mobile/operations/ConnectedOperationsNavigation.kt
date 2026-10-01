package com.nexa.mobile.operations

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.VerifiedContextAuthority
import com.nexa.mobile.operations.feature.warehouse.WarehouseUiState

/** Only entries with a connected implementation are supplied to the hub. */
internal data class ConnectedOperationEntry(
    val key: String,
    val label: String,
    val readPermissions: Set<String>,
    val visibleInHub: Boolean = true
) {
    init {
        require(key.isNotBlank() && label.isNotBlank() && readPermissions.isNotEmpty())
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
        ) return null
        val context = access.activeContext?.takeIf { it.isCurrent } ?: return null
        val authority = context.verifiedAuthority ?: return null
        if (listOf(authority.userId, authority.tenantId, authority.workspaceId, authority.membershipId)
            .any(String::isBlank)
        ) return null
        val operationalContext = warehouse.activeContext ?: return null
        if (operationalContext.authorityEpoch != access.authorityEpoch) return null
        val identity = operationalContext.verifiedIdentity ?: return null
        if (identity.userId != authority.userId || identity.tenantId != authority.tenantId ||
            identity.workspaceId != authority.workspaceId || identity.membershipId != authority.membershipId ||
            identity.permissions != authority.permissions
        ) return null
        return authority
    }

    fun visibleEntries(
        entries: List<ConnectedOperationEntry>,
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState
    ): List<ConnectedOperationEntry> {
        val authority = currentAuthority(session, access, warehouse) ?: return emptyList()
        return entries.filter { entry -> entry.visibleInHub && authority.permissions.any(entry.readPermissions::contains) }
    }

    fun open(
        entry: ConnectedOperationEntry,
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState
    ): ConnectedOperationRoute? {
        val authority = currentAuthority(session, access, warehouse) ?: return null
        if (authority.permissions.none(entry.readPermissions::contains)) return null
        return ConnectedOperationRoute(entry.key, access.authorityEpoch,
            authority.copy(permissions = authority.permissions.toSet()))
    }

    fun permits(
        route: ConnectedOperationRoute,
        entries: List<ConnectedOperationEntry>,
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState
    ): Boolean {
        val entry = entries.singleOrNull { it.key == route.entryKey } ?: return false
        if (route.authorityEpoch != access.authorityEpoch) return false
        val current = currentAuthority(session, access, warehouse) ?: return false
        return current == route.authority && current.permissions.any(entry.readPermissions::contains)
    }
}

@Composable
internal fun ConnectedOperationsEntries(
    entries: List<ConnectedOperationEntry>,
    onOpen: (ConnectedOperationEntry) -> Unit
) {
    entries.forEach { entry ->
        TextButton(onClick = { onOpen(entry) }) { Text(entry.label) }
    }
}
