package com.nexa.mobile.operations

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseUiState
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.designsystem.NexaTaskRow
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessStage
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessUiState

/** Reachable capability entries, including explicit unavailable states, use current authority. */
internal data class ConnectedOperationEntry(
    val key: String,
    val label: String,
    val readPermissions: Set<String>,
    val visibleInHub: Boolean = true,
    val readRoles: Set<String> = emptySet()
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
    onOpen: (ConnectedOperationEntry) -> Unit
) {
    entries.forEach { entry ->
        NexaTaskRow(
            title = entry.label,
            description = stringResource(R.string.connected_operation_open_support),
            onClick = { onOpen(entry) }
        )
    }
}
