package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.WorkforceContextSummary
import com.nexa.mobile.operations.feature.warehouse.WarehouseUiState
import com.nexa.mobile.operations.feature.warehouse.WorkEntryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WarehouseInvalidationRoutingTest {
    private val context = WorkforceContextSummary("membership", "Company", "Workspace")

    @Test
    fun staleSessionFailureCannotLogOutNewlySelectedContext() {
        val oldFailure = WarehouseUiState(
            workEntryStatus = WorkEntryStatus.SessionInvalidated,
            authorityEpoch = 4,
            invalidatedFromAuthorityEpoch = 3
        )
        val newlySelected = AccessUiState(
            stage = AccessStage.WorkAuthorized,
            activeContext = context,
            authorityEpoch = 5
        )

        assertNull(activeWarehouseInvalidation(SessionState.Active, newlySelected, oldFailure))
    }

    @Test
    fun currentSessionFailureStillRevokesCurrentAuthority() {
        val current = AccessUiState(
            stage = AccessStage.WorkAuthorized,
            activeContext = context,
            authorityEpoch = 5
        )
        for (failure in listOf(
            WorkEntryStatus.SessionInvalidated,
            WorkEntryStatus.ContextInvalidated
        )) {
            val warehouse = WarehouseUiState(
                workEntryStatus = failure,
                authorityEpoch = 6,
                invalidatedFromAuthorityEpoch = 5
            )
            assertEquals(
                failure,
                activeWarehouseInvalidation(SessionState.Active, current, warehouse)
            )
        }
    }

    @Test
    fun unscopedOrSignedOutFailureCannotRevokeAuthority() {
        val current = AccessUiState(
            stage = AccessStage.WorkAuthorized,
            activeContext = context,
            authorityEpoch = 5
        )
        val unscopedFailure = WarehouseUiState(
            workEntryStatus = WorkEntryStatus.SessionInvalidated,
            authorityEpoch = 6
        )
        val currentFailure = unscopedFailure.copy(invalidatedFromAuthorityEpoch = 5)

        assertNull(activeWarehouseInvalidation(SessionState.Active, current, unscopedFailure))
        assertNull(activeWarehouseInvalidation(SessionState.SignedOut, current, currentFailure))
        assertNull(
            activeWarehouseInvalidation(
                SessionState.Active,
                current.copy(stage = AccessStage.ContextChooser),
                currentFailure
            )
        )
    }
}
