package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class RootViewModel @Inject constructor(private val session: SessionCoordinator) : ViewModel() {
    val state = session.sessionState

    init {
        viewModelScope.launch { session.restore() }
    }

    fun retrySessionValidation() {
        viewModelScope.launch { session.retrySessionValidation() }
    }

    fun logout() {
        viewModelScope.launch { session.logout() }
    }
}
