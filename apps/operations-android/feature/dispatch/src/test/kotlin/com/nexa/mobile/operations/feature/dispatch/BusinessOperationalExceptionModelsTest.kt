package com.nexa.mobile.operations.feature.dispatch

import com.nexa.mobile.operations.feature.dispatch.model.BusinessOperationalException
import com.nexa.mobile.operations.feature.dispatch.model.BusinessOperationalExceptionAction
import com.nexa.mobile.operations.feature.dispatch.model.BusinessOperationalExceptionsSnapshot
import com.nexa.mobile.operations.feature.dispatch.model.businessOperationalExceptionRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessOperationalExceptionModelsTest {
    @Test
    fun lifecycleBodiesRequireReasonsAndFreezeOnlyContractFields() {
        assertEquals(
            "{\"reason\":\"inspect the report\"}",
            businessOperationalExceptionRequestBody(
                BusinessOperationalExceptionAction.CLAIM,
                reason = " inspect the report "
            )
        )
        assertEquals(
            "{\"reason\":\"recheck\",\"note\":\"contact warehouse\"}",
            businessOperationalExceptionRequestBody(
                BusinessOperationalExceptionAction.FOLLOW_UP,
                followUpNote = "contact warehouse",
                reason = "recheck"
            )
        )
        assertEquals(
            "{\"responsibleMembershipId\":\"$OTHER_MEMBER\",\"reason\":\"handoff to active coordinator\"}",
            businessOperationalExceptionRequestBody(
                BusinessOperationalExceptionAction.REASSIGN,
                responsibleMembershipId = OTHER_MEMBER,
                reason = "handoff to active coordinator"
            )
        )
        assertTrue(
            runCatching {
                businessOperationalExceptionRequestBody(BusinessOperationalExceptionAction.CLOSE)
            }.isFailure
        )
    }

    @Test
    fun coordinatorResolutionControlsOnlySupportedWarningLifecycle() {
        val row = exception(severity = "WARNING", type = "DELAY", status = "UNDER_REVIEW")
        val state = BusinessOperationalExceptionUiState(
            canRead = true,
            canCoordinate = true,
            currentMembershipId = MEMBER,
            snapshot = BusinessOperationalExceptionsSnapshot("2026-10-01T10:00:00Z", listOf(row)),
            selectedExceptionId = row.id,
            status = BusinessOperationalExceptionsStatus.Current
        )
        assertTrue(state.canResolve(row))
        assertTrue(state.canClose(row.copy(status = "RESOLVED")))
        assertFalse(state.canResolve(row.copy(severity = "CRITICAL")))
        assertFalse(state.canResolve(row.copy(type = "THERMAL_EXCURSION")))
        assertFalse(state.copy(canCoordinate = false).canResolve(row))
    }

    private fun exception(
        severity: String = "WARNING",
        type: String = "DELAY",
        status: String = "OPEN"
    ) = BusinessOperationalException(
        id = EXCEPTION,
        deliveryId = DELIVERY,
        deliveryVersion = 6,
        sourceKind = "DRIVER_INCIDENT",
        sourceIncidentId = INCIDENT,
        affectedObjectType = "DELIVERY",
        affectedObjectId = DELIVERY,
        type = type,
        severity = severity,
        status = status,
        reason = "reported delay",
        description = "Customer unavailable",
        place = null,
        resolution = null,
        outcome = null,
        reportedByMembershipId = MEMBER,
        occurredAt = "2026-10-01T09:00:00Z",
        reportedAt = "2026-10-01T09:05:00Z",
        responsibleMembershipId = MEMBER,
        claimedAt = null,
        underReviewByMembershipId = MEMBER,
        underReviewAt = "2026-10-01T09:10:00Z",
        coordinationOwnerMembershipId = MEMBER,
        coordinationClaimedAt = "2026-10-01T09:10:00Z",
        evidenceObjectIds = emptyList()
    )

    private companion object {
        const val DELIVERY = "00000000-0000-0000-0000-000000000010"
        const val EXCEPTION = "00000000-0000-0000-0000-000000000011"
        const val INCIDENT = "00000000-0000-0000-0000-000000000012"
        const val MEMBER = "00000000-0000-0000-0000-000000000013"
        const val OTHER_MEMBER = "00000000-0000-0000-0000-000000000014"
    }
}
