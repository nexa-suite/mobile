package com.nexa.mobile.operations.salescommitment.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderRead
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderStore
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderIntent
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderReceipt
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderRecord
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderDraft
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderLine
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Encrypted scope-bound drafts are unconfirmed; the frozen write body is never regenerated on replay. */
@Singleton
class AppDirectOrderStore @Inject constructor(@ApplicationContext context: Context) :
    DirectOrderStore {
    // Preserve the encrypted-storage namespace; intent operation markers govern compatibility.
    private val local =
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.FieldPurchaseRequest)
    private val mutex = Mutex()
    private fun CommercialAuthority.scope() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)
    override suspend fun load(authority: CommercialAuthority): DirectOrderRead = mutex.withLock {
        when (val stored = local.load(authority.scope())) {
            ScopedMetadataRead.Unavailable -> DirectOrderRead.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload =
                    stored.payload
                        ?: return@withLock DirectOrderRead.Available(DirectOrderRecord())
                val record =
                    DirectOrderRecordCodec.decode(payload)
                        ?: return@withLock DirectOrderRead.Unavailable
                val intent = record.intent
                if (intent?.outcome == "Pending") {
                    val recovered = record.copy(
                        intent = intent.copy(outcome = "UnknownOutcome")
                    )
                    if (!local.save(authority.scope(), DirectOrderRecordCodec.encode(recovered))) {
                        DirectOrderRead.Unavailable
                    } else {
                        DirectOrderRead.Available(recovered)
                    }
                } else {
                    DirectOrderRead.Available(record)
                }
            }
        }
    }
    override suspend fun save(authority: CommercialAuthority, record: DirectOrderRecord): Boolean =
        mutex.withLock {
            val stored = local.load(authority.scope())
            if (stored !is ScopedMetadataRead.Value) return@withLock false
            val before = stored.payload?.let(DirectOrderRecordCodec::decode)
            if (stored.payload != null && before == null) return@withLock false
            val prior = before?.intent
            val intent = record.intent
            if (prior?.operation == DirectOrderIntent.DIRECT_ORDER_OPERATION &&
                prior.outcome !in setOf(
                    "Confirmed",
                    "PrepaidPending",
                    "Conflict",
                    "PermissionDenied",
                    "Rejected",
                    "Unavailable"
                )
            ) {
                val changed = intent == null || intent.key != prior.key ||
                    intent.exactBody != prior.exactBody
                if (changed) return@withLock false
            }
            local.save(authority.scope(), DirectOrderRecordCodec.encode(record))
        }
}
