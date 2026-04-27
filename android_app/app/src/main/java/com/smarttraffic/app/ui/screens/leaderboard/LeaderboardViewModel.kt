package com.smarttraffic.app.ui.screens.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.core_engine.domain.model.LeaderboardEntry
import com.smarttraffic.core_engine.domain.usecase.ObserveLeaderboardUseCase
import com.smarttraffic.core_engine.security.SecureTokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val observeLeaderboardUseCase: ObserveLeaderboardUseCase,
    private val secureTokenStore: SecureTokenStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LeaderboardUiState())
    val state: StateFlow<LeaderboardUiState> = _state.asStateFlow()

    init {
        val currentUserId = secureTokenStore.currentUserId()
        _state.update {
            it.copy(
                isAuthenticated = currentUserId != null,
                currentUserId = currentUserId,
            )
        }

        viewModelScope.launch {
            observeLeaderboardUseCase().collectLatest { rows ->
                val ordered = rows.sortedBy { it.rank }
                val activeUserId = _state.value.currentUserId
                _state.update {
                    it.copy(
                        loading = false,
                        entries = ordered,
                        topThree = ordered.take(3),
                        myEntry = activeUserId?.let { userId ->
                            ordered.firstOrNull { entry -> entry.userId == userId }
                        },
                        liveUpdateEpochMs = System.currentTimeMillis(),
                    )
                }
            }
        }
    }
}

data class LeaderboardUiState(
    val loading: Boolean = true,
    val isAuthenticated: Boolean = false,
    val currentUserId: String? = null,
    val myEntry: LeaderboardEntry? = null,
    val topThree: List<LeaderboardEntry> = emptyList(),
    val entries: List<LeaderboardEntry> = emptyList(),
    val liveUpdateEpochMs: Long = 0L,
)

