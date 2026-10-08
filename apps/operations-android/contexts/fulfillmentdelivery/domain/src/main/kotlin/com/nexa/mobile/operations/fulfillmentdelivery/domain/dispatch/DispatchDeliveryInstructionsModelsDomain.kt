package com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch

data class DispatchDeliveryInstruction(
    val id: String,
    val kind: DispatchDeliveryInstructionKind,
    val content: String,
    val instructionVersion: Long,
    val critical: Boolean,
    val acknowledged: Boolean,
    val acknowledgedAt: String?,
    val acknowledgedByMembershipId: String?,
    val sourceKind: String?,
    val recordedByMembershipId: String?,
    val recordedAt: String?
)



enum class DispatchDeliveryInstructionKind {
    NORMAL,
    COLD_CHAIN,
    ACCESS_RESTRICTION,
    SPECIAL_UNLOADING,
    CUSTOMER_SAFETY,
    GOODS_HANDLING;

    val critical: Boolean get() = this != NORMAL
}



data class DispatchDeliveryInstructionsSnapshot(
    val deliveryId: String,
    val deliveryVersion: Long,
    val instructionSetVersion: Long,
    val instructions: List<DispatchDeliveryInstruction>
)



data class DispatchDeliveryInstructionReceipt(
    val deliveryId: String,
    val instructionId: String,
    val kind: DispatchDeliveryInstructionKind,
    val content: String,
    val instructionVersion: Long,
    val critical: Boolean,
    val deliveryVersion: Long,
    val instructionSetVersion: Long,
    val replayed: Boolean
)
