package com.nexa.mobile.operations.feature.commercial.application

import com.nexa.mobile.operations.feature.commercial.model.CommercialAuthority
import com.nexa.mobile.operations.feature.commercial.model.CustomerInstructionCommand
import com.nexa.mobile.operations.feature.commercial.model.CustomerInstructionSnapshot

sealed interface CustomerInstructionResult {
    data class Current(val snapshot: CustomerInstructionSnapshot) : CustomerInstructionResult
    data class Failed(val message: String, val unknown: Boolean = false) : CustomerInstructionResult
}

interface CustomerInstructionGateway {
    suspend fun read(authority: CommercialAuthority, orderId: String): CustomerInstructionResult
    suspend fun publish(
        authority: CommercialAuthority,
        command: CustomerInstructionCommand
    ): CustomerInstructionResult
}

sealed interface CustomerInstructionStored {
    data class Loaded(val command: CustomerInstructionCommand?) : CustomerInstructionStored
    data object Unavailable : CustomerInstructionStored
}

interface CustomerInstructionStore {
    suspend fun load(authority: CommercialAuthority): CustomerInstructionStored
    suspend fun save(authority: CommercialAuthority, command: CustomerInstructionCommand): Boolean
    suspend fun clear(authority: CommercialAuthority, key: String): Boolean
}
