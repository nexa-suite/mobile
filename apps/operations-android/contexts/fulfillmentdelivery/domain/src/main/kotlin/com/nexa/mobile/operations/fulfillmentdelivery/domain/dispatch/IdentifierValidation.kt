package com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch

private val opaqueIdentifierPattern =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Validates the opaque UUID-shaped identifiers used by fulfillment dispatch projections. */
fun isValidOpaqueIdentifier(value: String): Boolean = opaqueIdentifierPattern.matches(value)
