package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

/** Client gates for the server-issued handover capability. */
object DispatchHandoverPermissions {
    const val READ = "dispatch.read"
    const val CANONICAL_COMPLETE = "dispatch.complete"
    const val LOGISTICS_WRITE_ALIAS = "logistics:write"

    val complete = setOf(CANONICAL_COMPLETE, LOGISTICS_WRITE_ALIAS)

    fun allowsHandover(permissions: Set<String>): Boolean =
        READ in permissions && permissions.any(complete::contains)
}
