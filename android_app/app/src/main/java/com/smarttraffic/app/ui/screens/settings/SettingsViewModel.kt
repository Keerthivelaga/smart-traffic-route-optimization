package com.smarttraffic.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.core_engine.data.local.UserPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesStore: UserPreferencesStore,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        preferencesStore.highContrast,
        preferencesStore.hapticsEnabled,
        preferencesStore.textScalePct,
    ) { highContrast, haptics, scale ->
        SettingsUiState(highContrast = highContrast, haptics = haptics, textScalePct = scale)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setHighContrast(enabled: Boolean) {
        viewModelScope.launch { preferencesStore.setHighContrast(enabled) }
    }

    fun setHaptics(enabled: Boolean) {
        viewModelScope.launch { preferencesStore.setHapticsEnabled(enabled) }
    }

    fun setTextScale(percent: Int) {
        viewModelScope.launch { preferencesStore.setTextScale(percent) }
    }
}

data class SettingsUiState(
    val highContrast: Boolean = false,
    val haptics: Boolean = true,
    val textScalePct: Int = 100,
)

