package com.smarttraffic.core_engine.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.core_engine.presentation.mvi.UiEffect
import com.smarttraffic.core_engine.presentation.mvi.UiEvent
import com.smarttraffic.core_engine.presentation.mvi.UiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

abstract class BaseMviViewModel<S : UiState, E : UiEvent, F : UiEffect>(initial: S) : ViewModel() {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effects = Channel<F>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    protected fun updateState(reducer: (S) -> S) {
        _state.value = reducer(_state.value)
    }

    protected fun sendEffect(effect: F) {
        viewModelScope.launch { _effects.send(effect) }
    }

    abstract fun onEvent(event: E)
}

