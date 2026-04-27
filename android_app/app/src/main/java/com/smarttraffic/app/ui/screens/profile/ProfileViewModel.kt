package com.smarttraffic.app.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.core_engine.domain.model.UserProfile
import com.smarttraffic.core_engine.domain.usecase.GetProfileUseCase
import com.smarttraffic.core_engine.security.SecureTokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getProfileUseCase: GetProfileUseCase,
    private val secureTokenStore: SecureTokenStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        val currentUserId = secureTokenStore.currentUserId()
        val currentEmail = secureTokenStore.currentEmail()
        if (currentUserId.isNullOrBlank()) {
            _state.value = _state.value.copy(
                isAuthenticated = false,
                email = null,
                profile = GUEST_PROFILE,
                error = "Login to view your account details.",
            )
        } else {
            val resolvedUserId = currentUserId
            _state.value = _state.value.copy(
                isAuthenticated = true,
                email = currentEmail,
                profile = _state.value.profile.copy(
                    userId = resolvedUserId,
                    displayName = currentEmail ?: "Driver ${resolvedUserId.takeLast(4)}",
                ),
            )

            viewModelScope.launch {
                getProfileUseCase(resolvedUserId)
                    .onSuccess { profile ->
                        _state.value = _state.value.copy(
                            profile = profile.copy(
                                displayName = currentEmail ?: profile.displayName,
                            ),
                            error = null,
                        )
                    }
                    .onFailure { _state.value = _state.value.copy(error = it.message ?: "Unable to load profile") }

        }
        }


    }
    fun logout() {
        secureTokenStore.clear()

        _state.value = ProfileUiState(
            isAuthenticated = false,
            email = null,
            profile = GUEST_PROFILE,
            error = "You have been logged out."
        )
    }
}

data class ProfileUiState(
    val isAuthenticated: Boolean = false,
    val email: String? = null,
    val profile: UserProfile = UserProfile(
        userId = "guest",
        displayName = "Guest User",
        trustScore = 0f,
        reputation = 0f,
        achievements = emptyList(),
    ),
    val error: String? = null,
)

private val GUEST_PROFILE = UserProfile(
    userId = "guest",
    displayName = "Guest User",
    trustScore = 0f,
    reputation = 0f,
    achievements = emptyList(),
)

