package com.nexa.mobile.operations.payments.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.payments.application.refund.WalletRefundAuthority
import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntent
import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntentStage
import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntentStore
import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntentsRead
import com.nexa.mobile.operations.payments.application.refund.canReconcilePayments
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** AES-GCM records stay inside the exact verified membership scope. */
@Singleton
class AppWalletRefundIntentStore @Inject constructor(@ApplicationContext context: Context) :
    WalletRefundIntentStore {
    private val local = AndroidScopedMetadataStore(
        context,
        ScopedMetadataPurpose.WalletRefundCommand
    )
    private val mutex = Mutex()

    override suspend fun load(authority: WalletRefundAuthority): WalletRefundIntentsRead =
        mutex.withLock {
            if (!authority.canReconcilePayments()) {
                return@withLock WalletRefundIntentsRead.Unavailable
            }
            when (val stored = local.load(authority.scope())) {
                ScopedMetadataRead.Unavailable -> WalletRefundIntentsRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload = stored.payload
                        ?: return@withLock WalletRefundIntentsRead.Available(emptyList())
                    val intents = WalletRefundIntentCodec.decode(payload)
                        ?: return@withLock WalletRefundIntentsRead.Unavailable
                    WalletRefundIntentsRead.Available(intents)
                }
            }
        }

    override suspend fun stage(
        authority: WalletRefundAuthority,
        intent: WalletRefundIntent
    ): WalletRefundIntentStage = mutex.withLock {
        if (!authority.canReconcilePayments() || !WalletRefundIntentIntegrity.isValid(intent)) {
            return@withLock WalletRefundIntentStage.Unavailable
        }
        val current = read(authority) ?: return@withLock WalletRefundIntentStage.Unavailable
        if (current.any { it.salesOrderId == intent.salesOrderId }) {
            return@withLock WalletRefundIntentStage.SameOrderPending
        }
        if (current.any { it.idempotencyKey == intent.idempotencyKey }) {
            return@withLock WalletRefundIntentStage.Unavailable
        }
        if (local.save(authority.scope(), WalletRefundIntentCodec.encode(current + intent))) {
            WalletRefundIntentStage.Stored
        } else {
            WalletRefundIntentStage.Unavailable
        }
    }

    override suspend fun remove(
        authority: WalletRefundAuthority,
        intent: WalletRefundIntent
    ): Boolean = mutex.withLock {
        if (!authority.canReconcilePayments()) return@withLock false
        val current = read(authority) ?: return@withLock false
        val matching = current.singleOrNull { it.salesOrderId == intent.salesOrderId }
        when {
            matching == null -> true

            matching != intent -> false

            else -> {
                val remaining = current - matching
                if (remaining.isEmpty()) {
                    local.clear(authority.scope())
                } else {
                    local.save(authority.scope(), WalletRefundIntentCodec.encode(remaining))
                }
            }
        }
    }

    private suspend fun read(authority: WalletRefundAuthority): List<WalletRefundIntent>? =
        when (val stored = local.load(authority.scope())) {
            ScopedMetadataRead.Unavailable -> null

            is ScopedMetadataRead.Value -> when (val payload = stored.payload) {
                null -> emptyList()
                else -> WalletRefundIntentCodec.decode(payload)
            }
        }

    private fun WalletRefundAuthority.scope(): ScopedMetadataScope = ScopedMetadataScope(
        userId = identity.userId,
        tenantId = identity.tenantId,
        workspaceId = identity.workspaceId,
        membershipId = identity.membershipId
    )
}
