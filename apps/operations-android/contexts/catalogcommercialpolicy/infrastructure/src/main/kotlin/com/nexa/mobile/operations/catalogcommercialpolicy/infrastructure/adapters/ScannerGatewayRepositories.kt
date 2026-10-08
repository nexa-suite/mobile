package com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters

import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.ProductScannerResolution
import com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse.ProductScannerGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsContext
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsIdentity
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedScannedSku
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ScannerIdentifierType
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.NexaSkuIdentifierGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.SkuIdentifierResolutionOutcome
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.SkuIdentifierType
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.PermissionHint
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScannerGatewayBindings {
    @Provides
    @Singleton
    fun skuIdentifierGateway(protectedCalls: ProtectedCallExecutor): NexaSkuIdentifierGateway =
        NexaSkuIdentifierGateway(protectedCalls)
}

/** App adapter fences resolver responses against the exact verified workforce scope. */
@Singleton
class ScannerOperationsGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val identifiers: NexaSkuIdentifierGateway
) : ProductScannerGateway {
    override suspend fun resolve(
        candidate: String,
        authorityEpoch: Long,
        context: CatalogOperationsContext
    ): ProductScannerResolution {
        val expectedIdentity = context.verifiedIdentity
            ?: return ProductScannerResolution.ContextInvalidated
        if (context.authorityEpoch != authorityEpoch) {
            return ProductScannerResolution.ContextInvalidated
        }
        if (sessions.sessionState.value != SessionState.Active) {
            return ProductScannerResolution.SessionInvalidated
        }
        val access = sessions.currentAccess()
            ?: return ProductScannerResolution.SessionInvalidated
        val initial = sessions.verifiedSession.value
            ?: return ProductScannerResolution.ContextInvalidated
        if (!initial.hasAuthorizedContext || !initial.matches(expectedIdentity)) {
            return ProductScannerResolution.ContextInvalidated
        }
        if (scannerCatalogReadHint(initial.permissions) != PermissionHint.Available) {
            return ProductScannerResolution.PermissionDenied
        }

        val result = identifiers.resolve(candidate)
        if (!sessions.isEpochCurrent(access.epoch)) {
            return ProductScannerResolution.SessionInvalidated
        }
        val current = sessions.verifiedSession.value
            ?: return ProductScannerResolution.ContextInvalidated
        if (!current.hasAuthorizedContext || !current.matches(expectedIdentity)) {
            return ProductScannerResolution.ContextInvalidated
        }
        if (scannerCatalogReadHint(current.permissions) != PermissionHint.Available) {
            return ProductScannerResolution.PermissionDenied
        }

        return when (result) {
            is SkuIdentifierResolutionOutcome.Resolved -> {
                val sku = result.sku
                ProductScannerResolution.Resolved(
                    ConfirmedScannedSku(
                        skuId = sku.skuId,
                        skuCode = sku.skuCode,
                        gtin = sku.gtin,
                        presentation = sku.presentation,
                        unitOfMeasure = sku.unitOfMeasure,
                        status = sku.status,
                        identifierType = sku.identifierType.toScannerIdentifierType(),
                        context = context,
                        authorityEpoch = authorityEpoch
                    )
                )
            }

            is SkuIdentifierResolutionOutcome.NotFound -> ProductScannerResolution.NotFound

            is SkuIdentifierResolutionOutcome.Ambiguous ->
                ProductScannerResolution.Ambiguous(result.candidateCount)

            SkuIdentifierResolutionOutcome.InvalidIdentifier ->
                ProductScannerResolution.InvalidIdentifier

            SkuIdentifierResolutionOutcome.NetworkUnavailable ->
                ProductScannerResolution.NetworkUnavailable

            SkuIdentifierResolutionOutcome.ServiceUnavailable ->
                ProductScannerResolution.ServiceUnavailable

            SkuIdentifierResolutionOutcome.PermissionDenied ->
                ProductScannerResolution.PermissionDenied

            SkuIdentifierResolutionOutcome.ContextInvalidated -> {
                sessions.invalidateContext()
                ProductScannerResolution.ContextInvalidated
            }

            SkuIdentifierResolutionOutcome.SessionExpired -> {
                sessions.rejectCurrentAccess(access)
                ProductScannerResolution.SessionInvalidated
            }
        }
    }
}

private fun VerifiedSession.matches(identity: CatalogOperationsIdentity): Boolean =
    hasAuthorizedContext && userId == identity.userId && tenantId == identity.tenantId &&
        workspaceId == identity.workspaceId && membershipId == identity.membershipId &&
        permissions == identity.permissions

private fun scannerCatalogReadHint(permissions: Set<String>): PermissionHint = when {
    "catalog.read" in permissions || "catalog:read" in permissions -> PermissionHint.Available
    permissions.isEmpty() -> PermissionHint.Unknown
    else -> PermissionHint.Unavailable
}

private fun SkuIdentifierType.toScannerIdentifierType(): ScannerIdentifierType = when (this) {
    SkuIdentifierType.SKU_CODE -> ScannerIdentifierType.SkuCode
    SkuIdentifierType.GTIN -> ScannerIdentifierType.Gtin
    SkuIdentifierType.SKU_CODE_AND_GTIN -> ScannerIdentifierType.SkuCodeAndGtin
}
