package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

enum class TransferLookupStatus {
    NotRequested,
    Loading,
    Ready,
    Empty,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}
