package com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery

private val opaqueIdentifierPattern =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Validates the opaque UUID-shaped identifiers used by fulfillment delivery projections. */
fun isValidOpaqueIdentifier(value: String): Boolean = opaqueIdentifierPattern.matches(value)
