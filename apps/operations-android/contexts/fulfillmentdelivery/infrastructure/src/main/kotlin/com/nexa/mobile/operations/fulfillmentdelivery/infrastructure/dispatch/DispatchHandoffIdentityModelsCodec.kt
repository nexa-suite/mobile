package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch

fun dispatchHandoffIssueBody(assignmentId: String): String =

    """{"purpose":"DISPATCH_HANDOFF","assignmentId":"$assignmentId"}"""
