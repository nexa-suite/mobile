package com.nexa.mobile.operations.customerbuyerrelationships.application.commercial

import com.nexa.mobile.operations.customerbuyerrelationships.domain.model.commercial.CustomerRelationship
import com.nexa.mobile.operations.customerbuyerrelationships.domain.model.commercial.FieldVisitIntent
import com.nexa.mobile.operations.customerbuyerrelationships.domain.model.commercial.FieldVisitRecord
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import java.time.Instant

interface FieldVisitStore {
    suspend fun load(authority: CommercialAuthority): FieldVisitRecord?
    suspend fun save(authority: CommercialAuthority, record: FieldVisitRecord): Boolean
}

sealed interface FieldVisitResult {
    data class Recorded(val id: String) : FieldVisitResult
    data object Conflict : FieldVisitResult
    data object Denied : FieldVisitResult
    data object UnknownOutcome : FieldVisitResult
}

interface FieldVisitGateway {
    suspend fun customer(authority: CommercialAuthority, id: String): CustomerRelationship?
    fun body(purpose: String, followUp: String, occurredAt: Instant): String
    suspend fun record(authority: CommercialAuthority, intent: FieldVisitIntent): FieldVisitResult
}
