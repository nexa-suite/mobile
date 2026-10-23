package com.nexa.mobile.operations.salescommitment.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestDraftRead
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestDraftStore
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestDraft
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Encrypted local intention; this store has no command or replay behavior. */
@Singleton
class AppFieldRequestDraftStore @Inject constructor(@ApplicationContext context: Context) :
    FieldRequestDraftStore {
    private val local = AndroidScopedMetadataStore(context, ScopedMetadataPurpose.FieldRequestDraft)
    private val mutex = Mutex()

    private fun CommercialAuthority.scope() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    override suspend fun load(authority: CommercialAuthority): FieldRequestDraftRead =
        mutex.withLock {
            when (val stored = local.load(authority.scope())) {
                ScopedMetadataRead.Unavailable -> FieldRequestDraftRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload = stored.payload
                        ?: return@withLock FieldRequestDraftRead.Available(FieldRequestDraft())
                    val draft = FieldRequestDraftCodec.decode(payload)
                        ?: return@withLock FieldRequestDraftRead.Unavailable
                    FieldRequestDraftRead.Available(draft)
                }
            }
        }

    override suspend fun save(authority: CommercialAuthority, draft: FieldRequestDraft): Boolean =
        mutex.withLock {
            val scope = authority.scope()
            val stored = local.load(scope)
            if (stored !is ScopedMetadataRead.Value) return@withLock false
            val storedPayload = stored.payload
            if (storedPayload != null && FieldRequestDraftCodec.decode(storedPayload) == null) {
                return@withLock false
            }
            local.save(scope, FieldRequestDraftCodec.encode(draft))
        }
}
