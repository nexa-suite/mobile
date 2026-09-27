package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.warehouse.WarehouseUiState
import com.nexa.mobile.operations.feature.warehouse.WorkEntryStatus

/** An old session's failure must never revoke a newly selected context. */
internal fun activeWarehouseInvalidation(
    session: SessionState,
    access: AccessUiState,
    warehouse: WarehouseUiState
): WorkEntryStatus? {
    if (session != SessionState.Active || access.stage != AccessStage.WorkAuthorized ||
        access.activeContext == null ||
        warehouse.invalidatedFromAuthorityEpoch != access.authorityEpoch
    ) {
        return null
    }
    return warehouse.workEntryStatus.takeIf {
        it == WorkEntryStatus.ContextInvalidated || it == WorkEntryStatus.SessionInvalidated
    }
}
