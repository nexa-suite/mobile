package com.nexa.mobile.operations.tenantaccessgovernance.application.profile

import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.profile.OwnProfile
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.profile.OwnProfileEdit

interface OwnProfileGateway {
    suspend fun load(authority: VerifiedContextAuthority): OwnProfileResult
    suspend fun update(
        authority: VerifiedContextAuthority,
        current: OwnProfile,
        edit: OwnProfileEdit
    ): OwnProfileResult
}

sealed interface OwnProfileResult {
    data class Available(val profile: OwnProfile) : OwnProfileResult
    data object Denied : OwnProfileResult
    data object Invalid : OwnProfileResult
    data object Conflict : OwnProfileResult
    data object Unavailable : OwnProfileResult
}
