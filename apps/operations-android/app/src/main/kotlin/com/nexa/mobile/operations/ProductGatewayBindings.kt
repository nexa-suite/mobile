package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.IssuedNativeSession
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.AccessContextSelectionOutcome
import com.nexa.mobile.operations.core.network.AccessContextsOutcome
import com.nexa.mobile.operations.core.network.CatalogDetailOutcome
import com.nexa.mobile.operations.core.network.CatalogDetailProjection
import com.nexa.mobile.operations.core.network.CatalogSearchOutcome
import com.nexa.mobile.operations.core.network.IdentitySignInOutcome
import com.nexa.mobile.operations.core.network.NativeAccessContext
import com.nexa.mobile.operations.core.network.NativeAuthenticationSession
import com.nexa.mobile.operations.core.network.NexaCatalogGateway
import com.nexa.mobile.operations.core.network.NexaIdentityAccessGateway
import com.nexa.mobile.operations.feature.access.AccessGateway
import com.nexa.mobile.operations.feature.access.AccessViewModel
import com.nexa.mobile.operations.feature.access.ContextListResult
import com.nexa.mobile.operations.feature.access.ContextSelectionResult
import com.nexa.mobile.operations.feature.access.CurrentSessionContextResult
import com.nexa.mobile.operations.feature.access.PermissionHint
import com.nexa.mobile.operations.feature.access.SignInResult
import com.nexa.mobile.operations.feature.access.VerifiedContextAuthority
import com.nexa.mobile.operations.feature.access.WorkforceContextSummary
import com.nexa.mobile.operations.feature.warehouse.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.CandidateConfirmationResult
import com.nexa.mobile.operations.feature.warehouse.ConfirmedSkuUiState
import com.nexa.mobile.operations.feature.warehouse.ProductCandidate
import com.nexa.mobile.operations.feature.warehouse.ProductSearchResult
import com.nexa.mobile.operations.feature.warehouse.TaskVisibilityHint
import com.nexa.mobile.operations.feature.warehouse.WarehouseGateway
import com.nexa.mobile.operations.feature.warehouse.WarehouseViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

@Singleton
internal class OperationsAccessGateway @Inject constructor(
    private val identity: NexaIdentityAccessGateway,
    private val sessions: SessionCoordinator,
    private val catalog: NexaCatalogGateway
) : AccessGateway,
    WarehouseGateway {
    val currentContext = sessions.verifiedSession.map { it?.toWorkforceContext() }
    override suspend fun signIn(identifier: String, password: String): SignInResult =
        when (val result = identity.identitySignIn(identifier, password)) {
            is IdentitySignInOutcome.Authenticated -> {
                val context = establishContext(result.issuedSession, result.sessionContext, null)
                if (context ==
                    null
                ) {
                    SignInResult.ServiceUnavailable
                } else {
                    SignInResult.Authenticated(context)
                }
            }

            IdentitySignInOutcome.SelectionRequired -> SignInResult.SelectionRequired

            IdentitySignInOutcome.NoWorkContext -> SignInResult.NoWorkContext

            IdentitySignInOutcome.Rejected -> SignInResult.Rejected

            IdentitySignInOutcome.NetworkUnavailable -> SignInResult.NetworkUnavailable

            IdentitySignInOutcome.ServiceUnavailable,
            IdentitySignInOutcome.UnknownOutcome -> SignInResult.ServiceUnavailable
        }

    override suspend fun currentSessionContext(): CurrentSessionContextResult {
        if (sessions.sessionState.value != SessionState.Active) {
            return if (sessions.sessionState.value == SessionState.ContextRequired) {
                CurrentSessionContextResult.Invalid
            } else {
                CurrentSessionContextResult.Unavailable
            }
        }
        val verified = sessions.verifiedSession.value ?: return CurrentSessionContextResult.Invalid
        val context = verified.toWorkforceContext()
            ?: return CurrentSessionContextResult.Invalid
        return CurrentSessionContextResult.Available(context)
    }

    override suspend fun listContexts(): ContextListResult {
        val access = sessions.currentAccess()
        val result = identity.accessContexts(access?.value)
        if (result == AccessContextsOutcome.SessionExpired && access != null) {
            sessions.rejectCurrentAccess(access)
        }
        return when (result) {
            is AccessContextsOutcome.Available -> ContextListResult.Available(
                result.contexts.map { it.toWorkforceContext() }
            )

            AccessContextsOutcome.TicketExpired -> ContextListResult.TicketExpired

            AccessContextsOutcome.SessionExpired -> ContextListResult.SessionExpired

            AccessContextsOutcome.Rejected -> ContextListResult.ServiceUnavailable

            AccessContextsOutcome.NetworkUnavailable -> ContextListResult.NetworkUnavailable

            AccessContextsOutcome.ServiceUnavailable -> ContextListResult.ServiceUnavailable
        }
    }

    override suspend fun selectContext(context: WorkforceContextSummary): ContextSelectionResult {
        val access = sessions.currentAccess()
        val result = identity.selectAccessContext(context.key, access?.value)
        if (result == AccessContextSelectionOutcome.SessionExpired && access != null) {
            sessions.rejectCurrentAccess(access)
        }
        return when (result) {
            is AccessContextSelectionOutcome.Authenticated -> {
                val verified = establishContext(
                    result.issuedSession,
                    result.sessionContext,
                    context.key
                )
                if (verified == null) {
                    ContextSelectionResult.UnknownOutcome
                } else {
                    ContextSelectionResult.Confirmed(verified)
                }
            }

            AccessContextSelectionOutcome.Rejected -> ContextSelectionResult.Rejected

            AccessContextSelectionOutcome.SessionExpired -> ContextSelectionResult.SessionExpired

            AccessContextSelectionOutcome.UnknownOutcome -> ContextSelectionResult.UnknownOutcome
        }
    }

    override suspend fun search(
        query: String,
        pageKey: String?,
        authorityEpoch: Long
    ): ProductSearchResult {
        val access = sessions.currentAccess() ?: return ProductSearchResult.SessionInvalidated
        val context = sessions.verifiedSession.value
            ?: return ProductSearchResult.ContextInvalidated
        val result = catalog.search(query, pageKey)
        if (!sessions.isEpochCurrent(access.epoch)) return ProductSearchResult.SessionInvalidated
        val current = sessions.verifiedSession.value
            ?: return ProductSearchResult.ContextInvalidated
        if (!contextIsCurrent(context, current)) return ProductSearchResult.ContextInvalidated
        if (catalogReadHint(current.permissions) ==
            PermissionHint.Unavailable
        ) {
            return ProductSearchResult.PermissionDenied
        }
        return when (result) {
            is CatalogSearchOutcome.Page -> ProductSearchResult.Page(
                result.value.candidates.map {
                    ProductCandidate(
                        key = it.catalogItemId,
                        productDisplayName = it.productFamilyName ?: it.itemName,
                        brandOrVariant = listOfNotNull(it.brandName, it.productVariantName)
                            .distinct().joinToString(" · ").takeIf(String::isNotBlank),
                        presentation = it.presentation,
                        sku = it.skuCode,
                        imageFileName = it.imageFileName
                    )
                },
                result.value.nextPageKey
            )

            CatalogSearchOutcome.InvalidQuery -> ProductSearchResult.InvalidQuery

            CatalogSearchOutcome.NetworkUnavailable -> ProductSearchResult.NetworkUnavailable

            CatalogSearchOutcome.ServiceUnavailable -> ProductSearchResult.ServiceUnavailable

            CatalogSearchOutcome.PermissionDenied -> ProductSearchResult.PermissionDenied

            CatalogSearchOutcome.ContextInvalidated -> {
                sessions.invalidateContext()
                ProductSearchResult.ContextInvalidated
            }

            CatalogSearchOutcome.SessionExpired -> ProductSearchResult.SessionInvalidated
        }
    }

    override suspend fun confirm(
        candidate: ProductCandidate,
        authorityEpoch: Long,
        context: ActiveOperationsContext
    ): CandidateConfirmationResult {
        val access = sessions.currentAccess()
            ?: return CandidateConfirmationResult.SessionInvalidated
        val verified = sessions.verifiedSession.value
            ?: return CandidateConfirmationResult.ContextInvalidated
        val result = catalog.loadDetail(candidate.key)
        if (!sessions.isEpochCurrent(access.epoch)) {
            return CandidateConfirmationResult.SessionInvalidated
        }
        val current = sessions.verifiedSession.value
            ?: return CandidateConfirmationResult.ContextInvalidated
        if (!contextIsCurrent(
                verified,
                current
            )
        ) {
            return CandidateConfirmationResult.ContextInvalidated
        }
        if (catalogReadHint(current.permissions) ==
            PermissionHint.Unavailable
        ) {
            return CandidateConfirmationResult.PermissionDenied
        }
        return when (result) {
            is CatalogDetailOutcome.Found -> mapConfirmedCatalogDetail(
                candidate = candidate,
                detail = result.value,
                context = context,
                authorityEpoch = authorityEpoch
            )

            CatalogDetailOutcome.CandidateUnavailable ->
                CandidateConfirmationResult.CandidateUnavailable

            CatalogDetailOutcome.NetworkUnavailable ->
                CandidateConfirmationResult.NetworkUnavailable

            CatalogDetailOutcome.ServiceUnavailable ->
                CandidateConfirmationResult.ServiceUnavailable

            CatalogDetailOutcome.PermissionDenied -> CandidateConfirmationResult.PermissionDenied

            CatalogDetailOutcome.ContextInvalidated -> {
                sessions.invalidateContext()
                CandidateConfirmationResult.ContextInvalidated
            }

            CatalogDetailOutcome.SessionExpired -> CandidateConfirmationResult.SessionInvalidated
        }
    }

    private suspend fun establishContext(
        issued: IssuedNativeSession,
        expected: NativeAuthenticationSession,
        selectedMembershipId: String?
    ): WorkforceContextSummary? {
        if (expected.surface != "PLATFORM" ||
            expected.userId.isNullOrBlank() ||
            expected.tenantId.isNullOrBlank() ||
            expected.workspaceId.isNullOrBlank() ||
            expected.membershipId.isNullOrBlank() ||
            (selectedMembershipId != null && expected.membershipId != selectedMembershipId)
        ) {
            return null
        }
        if (!sessions.establish(issued)) return null
        val verified = sessions.verifiedSession.value ?: return null
        if (!verified.matches(expected)) {
            sessions.invalidateContext()
            return null
        }
        return verified.toWorkforceContext()
    }

    private fun VerifiedSession.matches(expected: NativeAuthenticationSession): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId

    private fun NativeAccessContext.toWorkforceContext() = WorkforceContextSummary(
        key = membershipId,
        companyName = tenantName,
        workspaceName = workspaceName,
        permissionHint = PermissionHint.Unknown
    )
}

internal fun mapConfirmedCatalogDetail(
    candidate: ProductCandidate,
    detail: CatalogDetailProjection,
    context: ActiveOperationsContext,
    authorityEpoch: Long
): CandidateConfirmationResult {
    if (detail.catalogItemId != candidate.key || detail.skuCode != candidate.sku) {
        return CandidateConfirmationResult.CandidateUnavailable
    }
    return CandidateConfirmationResult.Confirmed(
        ConfirmedSkuUiState(
            candidateKey = candidate.key,
            productDisplayName = detail.productFamilyName ?: detail.itemName,
            variant = detail.productVariantName,
            presentation = detail.presentation,
            sku = detail.skuCode,
            brand = detail.brandName,
            unit = detail.unitOfMeasure,
            packaging = detail.packagingType,
            coldChain = detail.coldChainRequirement,
            context = context,
            authorityEpoch = authorityEpoch,
            imageFileName = detail.imageFileName
        )
    )
}

internal fun contextIsCurrent(expected: VerifiedSession, current: VerifiedSession): Boolean =
    current.hasAuthorizedContext && current.userId == expected.userId &&
        current.tenantId == expected.tenantId && current.workspaceId == expected.workspaceId &&
        current.membershipId == expected.membershipId &&
        current.permissions == expected.permissions

internal fun VerifiedSession.toWorkforceContext(): WorkforceContextSummary? {
    if (!hasAuthorizedContext) return null
    val membership = membershipId?.takeIf(String::isNotBlank) ?: return null
    val user = userId?.takeIf(String::isNotBlank) ?: return null
    val tenantId = tenantId?.takeIf(String::isNotBlank) ?: return null
    val workspaceId = workspaceId?.takeIf(String::isNotBlank) ?: return null
    val tenant = tenantName?.takeIf(String::isNotBlank) ?: return null
    val workspace = workspaceName?.takeIf(String::isNotBlank) ?: return null
    val permissionHint = catalogReadHint(permissions)
    return WorkforceContextSummary(
        key = membership,
        companyName = tenant,
        workspaceName = workspace,
        permissionHint = permissionHint,
        verifiedAuthority = VerifiedContextAuthority(
            userId = user,
            tenantId = tenantId,
            workspaceId = workspaceId,
            membershipId = membership,
            permissions = permissions.toSet()
        )
    )
}

internal fun catalogReadHint(permissions: Set<String>): PermissionHint = when {
    "catalog.read" in permissions || "catalog:read" in permissions -> PermissionHint.Available
    permissions.isEmpty() -> PermissionHint.Unknown
    else -> PermissionHint.Unavailable
}

internal class AccessViewModelFactory(private val gateway: AccessGateway) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AccessViewModel::class.java))
        return AccessViewModel(gateway) as T
    }
}

internal class WarehouseViewModelFactory(private val gateway: WarehouseGateway) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(WarehouseViewModel::class.java))
        return WarehouseViewModel(gateway) as T
    }
}
