package com.nexa.mobile.operations.feature.commercial.application

import com.nexa.mobile.operations.feature.commercial.model.CommercialAuthority
import com.nexa.mobile.operations.feature.commercial.model.CustomerRelationship
import com.nexa.mobile.operations.feature.commercial.model.FieldVisitIntent
import com.nexa.mobile.operations.feature.commercial.model.FieldVisitRecord
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
