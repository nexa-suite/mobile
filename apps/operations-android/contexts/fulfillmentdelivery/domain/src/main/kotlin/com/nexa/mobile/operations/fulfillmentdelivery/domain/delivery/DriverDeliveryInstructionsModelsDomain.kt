package com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery

enum class DriverDeliveryInstructionKind {
    NORMAL,
    COLD_CHAIN,
    ACCESS_RESTRICTION,
    SPECIAL_UNLOADING,
    CUSTOMER_SAFETY,
    GOODS_HANDLING
}

data class DriverDeliveryInstruction(
    val id: String,
    val kind: DriverDeliveryInstructionKind,
    val content: String,
    val instructionVersion: Long,
    val critical: Boolean,
    val acknowledged: Boolean,
    val acknowledgedAt: String?,
    val acknowledgedByMembershipId: String?,
    val sourceKind: String? = null,
    val recordedByMembershipId: String? = null,
    val recordedAt: String? = null
) {
    init {
        require(isValidOpaqueIdentifier(id))
        require(content.isNotBlank())
        require(instructionVersion >= 0)
        require(critical == (kind != DriverDeliveryInstructionKind.NORMAL))
        require(
            acknowledged ==
                (!acknowledgedAt.isNullOrBlank() && !acknowledgedByMembershipId.isNullOrBlank())
        )
    }
}

data class DriverDeliveryInstructionsSnapshot(
    val deliveryId: String,
    val deliveryVersion: Long,
    val instructionSetVersion: Long,
    val instructions: List<DriverDeliveryInstruction>
) {
    init {
        require(isValidOpaqueIdentifier(deliveryId))
        require(deliveryVersion >= 0 && instructionSetVersion >= 0)
        require(instructions.map { it.id.lowercase() }.distinct().size == instructions.size)
    }
}

data class DriverDeliveryInstructionAcknowledgementFact(
    val instructionId: String,
    val instructionVersion: Long,
    val acknowledgedByMembershipId: String,
    val acknowledgedAt: String
) {
    init {
        require(isValidOpaqueIdentifier(instructionId))
        require(instructionVersion >= 0)
        require(isValidOpaqueIdentifier(acknowledgedByMembershipId))
        require(acknowledgedAt.isNotBlank())
    }
}

data class DriverDeliveryInstructionAcknowledgementSummary(
    val deliveryId: String,
    val instructionSetVersion: Long,
    val acknowledgements: List<DriverDeliveryInstructionAcknowledgementFact>,
    val replayed: Boolean
) {
    init {
        require(isValidOpaqueIdentifier(deliveryId))
        require(instructionSetVersion >= 0)
        require(
            acknowledgements.map { it.instructionId.lowercase() }.distinct().size ==
                acknowledgements.size
        )
    }
}
