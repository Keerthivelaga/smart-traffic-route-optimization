package com.smarttraffic.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.core_engine.data.local.UserPreferencesStore
import com.smarttraffic.core_engine.security.TamperGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class AppViewModel @Inject constructor(
    prefs: UserPreferencesStore,
    tamperGuard: TamperGuard,
) : ViewModel() {

    val uiState: StateFlow<AppUiState> = combine(
        prefs.highContrast,
        prefs.textScalePct,
        prefs.hapticsEnabled,
    ) { highContrast, textScalePct, hapticsEnabled ->
        AppUiState(
            highContrast = highContrast,
            textScalePct = textScalePct,
            hapticsEnabled = hapticsEnabled,
            compromised = tamperGuard.isCompromised(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppUiState(),
    )
}

data class AppUiState(
    val highContrast: Boolean = false,
    val textScalePct: Int = 100,
    val hapticsEnabled: Boolean = true,
    val compromised: Boolean = false,
)

