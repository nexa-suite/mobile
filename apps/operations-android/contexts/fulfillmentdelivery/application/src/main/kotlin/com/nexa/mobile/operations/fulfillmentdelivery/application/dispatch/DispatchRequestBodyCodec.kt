package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DeliveryLoad
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DeliveryLoadCompatibilityAttestation
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDeliveryInstructionKind
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.PreparedFulfillmentDriverAssignment
import java.time.Instant

/** Infrastructure-owned JSON encoding and frozen-body validation for dispatch requests. */
interface DispatchRequestBodyCodec {
    fun businessOperationalExceptionRequestBody(
        action: BusinessOperationalExceptionAction,
        responsibleMembershipId: String? = null,
        followUpNote: String? = null,
        reason: String? = null
    ): String

    fun isValid(command: BusinessOperationalExceptionCommand): Boolean

    fun dispatchDeliveryInstructionRequestBody(
        instructionId: String?,
        kind: DispatchDeliveryInstructionKind,
        content: String
    ): String

    fun isValid(intent: DispatchDeliveryInstructionIntent): Boolean

    fun deliveryLoadCreationBody(
        readiness: List<DispatchReadiness>,
        stopOrder: List<String>,
        reason: String,
        attestation: DeliveryLoadCompatibilityAttestation
    ): String

    fun dispatchWindowPlanBody(windowStart: String, windowEnd: String, reason: String): String
    fun deliveryLoadAssignBody(driverMembershipId: String): String
    fun deliveryLoadReorderBody(stopOrder: List<String>, reason: String): String
    fun isValid(command: DeliveryLoadCommand): Boolean
    fun matches(load: DeliveryLoad, command: DeliveryLoadCommand, membershipId: String?): Boolean

    fun dispatchHandoffIssueBody(assignmentId: String): String
    fun isValid(command: DispatchHandoffIdentityCommand): Boolean
    fun dispatchHandoverRequestBody(command: DispatchHandoverCommand): String
    fun isValid(command: DispatchHandoverCommand): Boolean
    fun dispatchOutgoingGoodsRequestBody(command: DispatchOutgoingGoodsCommand): String
    fun dispatchOutgoingGoodsObservationsRequestBody(
        allocationId: String,
        allocationVersion: Long,
        observations: List<DispatchOutgoingGoodsObservation>
    ): String
    fun isValid(command: DispatchOutgoingGoodsCommand): Boolean
    fun dispatchPlanChangeRequestBody(
        assignment: PreparedFulfillmentDriverAssignment,
        readiness: DispatchReadiness,
        membershipId: String?,
        dispatchAt: Instant?
    ): String
    fun isValid(intent: DispatchPlanChangeIntent): Boolean
    fun dispatchTemperatureRequestBody(command: DispatchTemperatureCommand): String
    fun isValid(command: DispatchTemperatureCommand): Boolean
}
