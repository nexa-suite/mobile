package com.nexa.mobile.operations.feature.warehouse

enum class TemperatureLookupStatus {
    NotRequested,
    Loading,
    Ready,
    Empty,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    Stale,
    Rejected
}

enum class TemperaturePhotoStatus {
    None,
    Uploading,
    Checking,
    AwaitingAvailability,
    Available,
    UnknownOutcome,
    NetworkUnavailable,
    ServiceUnavailable,
    Rejected,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}
