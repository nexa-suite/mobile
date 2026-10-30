package com.nexa.mobile.operations.tenantaccessgovernance.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.tenantaccessgovernance.application.profile.OwnProfileGateway
import com.nexa.mobile.operations.tenantaccessgovernance.application.profile.OwnProfileResult
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.profile.OwnProfile
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.profile.OwnProfileEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OwnProfileNotice { Saved, Invalid, Denied, Conflict, Unavailable }
data class OwnProfileState(
    val profile: OwnProfile? = null,
    val busy: Boolean = false,
    val mustReload: Boolean = false,
    val notice: OwnProfileNotice? = null
)
class OwnProfileViewModel(private val gateway: OwnProfileGateway) : ViewModel() {
    private val mutableState = MutableStateFlow(OwnProfileState())
    val state = mutableState.asStateFlow()
    private var authority: VerifiedContextAuthority? = null
    private var generation = 0L
    fun activate(value: VerifiedContextAuthority) {
        deactivate()
        authority = value.copy(permissions = value.permissions.toSet(), roles = value.roles.toSet())
        load()
    }
    fun deactivate() {
        generation++
        authority = null
        mutableState.value = OwnProfileState()
    }
    fun load() = request(null)
    fun save(edit: OwnProfileEdit) {
        if (state.value.profile == null || state.value.mustReload) return
        request(edit)
    }
    private fun request(edit: OwnProfileEdit?) {
        val scope = authority ?: return
        val old = state.value
        if (old.busy) return
        val request = ++generation
        mutableState.value = old.copy(busy = true, notice = null)
        viewModelScope.launch {
            val result = try {
                if (edit ==
                    null
                ) {
                    gateway.load(scope)
                } else {
                    gateway.update(scope, old.profile!!, edit)
                }
            } catch (
                cancelled: CancellationException
            ) {
                throw cancelled
            } catch (_: Exception) {
                OwnProfileResult.Unavailable
            }
            if (generation != request || authority != scope) return@launch
            mutableState.value = when (result) {
                is OwnProfileResult.Available -> OwnProfileState(
                    profile = result.profile,
                    notice = if (edit == null) null else OwnProfileNotice.Saved
                )

                OwnProfileResult.Invalid -> old.copy(
                    busy = false,
                    notice = OwnProfileNotice.Invalid
                )

                OwnProfileResult.Denied -> OwnProfileState(notice = OwnProfileNotice.Denied)

                OwnProfileResult.Conflict -> old.copy(
                    busy = false,
                    mustReload = true,
                    notice = OwnProfileNotice.Conflict
                )

                OwnProfileResult.Unavailable -> old.copy(
                    busy = false,
                    mustReload =
                        edit != null || old.mustReload,
                    notice = OwnProfileNotice.Unavailable
                )
            }
        }
    }
}
