package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverWorkday
import java.time.Instant

sealed interface DriverWorkdayReadResult {
    data class Current(val workday: DriverWorkday?) : DriverWorkdayReadResult
    data object Unavailable : DriverWorkdayReadResult
    data object PermissionDenied : DriverWorkdayReadResult
    data object ContextInvalidated : DriverWorkdayReadResult
    data object SessionInvalidated : DriverWorkdayReadResult
}

sealed interface DriverWorkdayCommandResult {
    data object Accepted : DriverWorkdayCommandResult
    data class Rejected(val code: String?) : DriverWorkdayCommandResult
    data object NotFound : DriverWorkdayCommandResult
    data object StaleVersion : DriverWorkdayCommandResult
    data object UnknownOutcome : DriverWorkdayCommandResult
    data object Unavailable : DriverWorkdayCommandResult
    data object PermissionDenied : DriverWorkdayCommandResult
    data object ContextInvalidated : DriverWorkdayCommandResult
    data object SessionInvalidated : DriverWorkdayCommandResult
}

enum class DriverWorkdayCommandAction {
    START,
    SET_LOCATION_AVAILABILITY,
    END
}

data class DriverWorkdayCommandScope(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    init {
        require(listOf(userId, tenantId, workspaceId, membershipId).all(String::isNotBlank))
    }
}

data class DriverWorkdayCommandIntent(
    val scope: DriverWorkdayCommandScope,
    val action: DriverWorkdayCommandAction,
    val workdayId: String?,
    val expectedVersion: Long?,
    val locationAvailable: Boolean?,
    val idempotencyKey: String,
    val initiatedAt: String
) {
    init {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(runCatching { Instant.parse(initiatedAt) }.isSuccess)
        when (action) {
            DriverWorkdayCommandAction.START -> require(
                workdayId == null && expectedVersion == null && locationAvailable == true
            )

            DriverWorkdayCommandAction.END -> require(
                workdayId != null && expectedVersion != null && expectedVersion >= 0 &&
                    locationAvailable == null
            )

            DriverWorkdayCommandAction.SET_LOCATION_AVAILABILITY -> require(
                workdayId != null && expectedVersion != null && expectedVersion >= 0 &&
                    locationAvailable != null
            )
        }
    }

    override fun toString(): String = "DriverWorkdayCommandIntent(action=$action, key=REDACTED)"
}

sealed interface DriverWorkdayCommandIntentRead {
    data class Available(val intent: DriverWorkdayCommandIntent?) : DriverWorkdayCommandIntentRead
    data object Unavailable : DriverWorkdayCommandIntentRead
}
