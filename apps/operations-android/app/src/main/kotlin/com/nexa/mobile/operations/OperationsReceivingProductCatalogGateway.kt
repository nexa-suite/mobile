package com.nexa.mobile.operations

import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.CatalogDetailOutcome
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.CatalogFilterOptionsOutcome
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.CatalogSearchOutcome
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.NexaCatalogGateway
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.auth.session.contextIsCurrent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogCandidate
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogFilterChoice
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogFilterKind
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogFilterPage
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogOutcome
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogPage
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.ReceivingProductCatalogGateway
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingProductReference
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.PermissionHint
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.catalogReadHint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Binds receiving catalog reads to the currently verified Operations context. */
@Singleton
internal class OperationsReceivingProductCatalogGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val catalog: NexaCatalogGateway
) : ReceivingProductCatalogGateway {
    override suspend fun search(
        query: String,
        brandName: String?,
        categoryName: String?,
        pageKey: String?,
        authority: ReceivingAuthority
    ): ReceivingCatalogOutcome<ReceivingCatalogPage> = withAuthority(authority) {
        when (val result = catalog.search(query, pageKey, brandName, categoryName)) {
            is CatalogSearchOutcome.Page -> ReceivingCatalogOutcome.Success(
                ReceivingCatalogPage(
                    items = result.value.candidates.map { candidate ->
                        ReceivingCatalogCandidate(
                            catalogItemId = candidate.catalogItemId,
                            itemName = candidate.itemName,
                            presentation = candidate.presentation,
                            skuCode = candidate.skuCode,
                            brandName = candidate.brandName,
                            categoryName = candidate.categoryName
                        )
                    },
                    nextPageKey = result.value.nextPageKey
                )
            )

            CatalogSearchOutcome.InvalidQuery -> ReceivingCatalogOutcome.InvalidQuery

            CatalogSearchOutcome.NetworkUnavailable ->
                ReceivingCatalogOutcome.NetworkUnavailable

            CatalogSearchOutcome.ServiceUnavailable ->
                ReceivingCatalogOutcome.ServiceUnavailable

            CatalogSearchOutcome.PermissionDenied -> ReceivingCatalogOutcome.PermissionDenied

            CatalogSearchOutcome.ContextInvalidated -> ReceivingCatalogOutcome.ContextInvalidated

            CatalogSearchOutcome.SessionExpired -> ReceivingCatalogOutcome.SessionInvalidated
        }
    }

    override suspend fun filterOptions(
        kind: ReceivingCatalogFilterKind,
        search: String,
        page: Int,
        authority: ReceivingAuthority
    ): ReceivingCatalogOutcome<ReceivingCatalogFilterPage> = withAuthority(authority) {
        when (val result = catalog.filterOptions(kind.wireValue, search, page)) {
            is CatalogFilterOptionsOutcome.Page -> ReceivingCatalogOutcome.Success(
                ReceivingCatalogFilterPage(
                    items = result.items.map { ReceivingCatalogFilterChoice(it.id, it.name) },
                    page = result.page,
                    size = result.size,
                    total = result.total
                )
            )

            CatalogFilterOptionsOutcome.InvalidQuery -> ReceivingCatalogOutcome.InvalidQuery

            CatalogFilterOptionsOutcome.NetworkUnavailable ->
                ReceivingCatalogOutcome.NetworkUnavailable

            CatalogFilterOptionsOutcome.ServiceUnavailable ->
                ReceivingCatalogOutcome.ServiceUnavailable

            CatalogFilterOptionsOutcome.PermissionDenied ->
                ReceivingCatalogOutcome.PermissionDenied

            CatalogFilterOptionsOutcome.ContextInvalidated ->
                ReceivingCatalogOutcome.ContextInvalidated

            CatalogFilterOptionsOutcome.SessionExpired ->
                ReceivingCatalogOutcome.SessionInvalidated
        }
    }

    override suspend fun detail(
        candidate: ReceivingCatalogCandidate,
        authority: ReceivingAuthority
    ): ReceivingCatalogOutcome<ReceivingProductReference> = withAuthority(authority) {
        when (val result = catalog.loadDetail(candidate.catalogItemId)) {
            is CatalogDetailOutcome.Found -> {
                val detail = result.value
                val unit = detail.unitOfMeasure?.trim()?.takeIf(String::isNotEmpty)
                if (detail.catalogItemId != candidate.catalogItemId ||
                    detail.skuCode != candidate.skuCode
                ) {
                    ReceivingCatalogOutcome.CandidateUnavailable
                } else if (unit == null) {
                    ReceivingCatalogOutcome.ServiceUnavailable
                } else {
                    ReceivingCatalogOutcome.Success(
                        ReceivingProductReference(
                            catalogItemId = detail.catalogItemId,
                            skuId = null,
                            displayName = detail.itemName,
                            skuCode = detail.skuCode,
                            unit = unit
                        )
                    )
                }
            }

            CatalogDetailOutcome.CandidateUnavailable ->
                ReceivingCatalogOutcome.CandidateUnavailable

            CatalogDetailOutcome.NetworkUnavailable ->
                ReceivingCatalogOutcome.NetworkUnavailable

            CatalogDetailOutcome.ServiceUnavailable ->
                ReceivingCatalogOutcome.ServiceUnavailable

            CatalogDetailOutcome.PermissionDenied -> ReceivingCatalogOutcome.PermissionDenied

            CatalogDetailOutcome.ContextInvalidated -> ReceivingCatalogOutcome.ContextInvalidated

            CatalogDetailOutcome.SessionExpired -> ReceivingCatalogOutcome.SessionInvalidated
        }
    }

    private suspend fun <T> withAuthority(
        authority: ReceivingAuthority,
        action: suspend () -> ReceivingCatalogOutcome<T>
    ): ReceivingCatalogOutcome<T> {
        val initial = authorize(authority)
        if (initial !is Authorization.Current) return initial.failure()
        val result = try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReceivingCatalogOutcome.ServiceUnavailable
        }
        return authorizationAfter(authority, initial) ?: result
    }

    private suspend fun authorize(authority: ReceivingAuthority): Authorization {
        if (sessions.sessionState.value != SessionState.Active) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (!verified.matches(authority)) return Authorization.ContextInvalidated
        if (!authority.canReceive || catalogReadHint(authority.permissions) ==
            PermissionHint.Unavailable
        ) {
            return Authorization.PermissionDenied
        }
        return Authorization.Current(lease, verified)
    }

    private suspend fun authorizationAfter(
        authority: ReceivingAuthority,
        previous: Authorization.Current
    ): ReceivingCatalogOutcome<Nothing>? {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(previous.lease.epoch)
        ) {
            return ReceivingCatalogOutcome.SessionInvalidated
        }
        val current = sessions.verifiedSession.value
            ?: return ReceivingCatalogOutcome.ContextInvalidated
        if (!contextIsCurrent(previous.session, current) || !current.matches(authority)) {
            return ReceivingCatalogOutcome.ContextInvalidated
        }
        return if (catalogReadHint(current.permissions) == PermissionHint.Unavailable) {
            ReceivingCatalogOutcome.PermissionDenied
        } else {
            null
        }
    }

    private fun VerifiedSession.matches(authority: ReceivingAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.failure(): ReceivingCatalogOutcome<Nothing> = when (this) {
        Authorization.SessionInvalidated -> ReceivingCatalogOutcome.SessionInvalidated
        Authorization.ContextInvalidated -> ReceivingCatalogOutcome.ContextInvalidated
        Authorization.PermissionDenied -> ReceivingCatalogOutcome.PermissionDenied
        is Authorization.Current -> error("current authority is not a failure")
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease, val session: VerifiedSession) :
            Authorization

        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }
}
