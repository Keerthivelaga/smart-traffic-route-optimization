package com.smarttraffic.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.app.BuildConfig
import com.smarttraffic.core_engine.data.remote.BackendApi
import com.smarttraffic.core_engine.data.remote.FirebaseEmailAuthRequestDto
import com.smarttraffic.core_engine.data.remote.FirebaseIdentityApi
import com.smarttraffic.core_engine.security.SecureTokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.regex.Pattern
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val secureTokenStore: SecureTokenStore,
    private val backendApi: BackendApi,
    private val firebaseIdentityApi: FirebaseIdentityApi,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun updateMode(mode: AuthMode) {
        _state.value = _state.value.copy(
            mode = mode,
            error = null,
            info = null,
        )
    }

    fun updateEmail(value: String) {
        _state.value = _state.value.copy(
            email = value,
            error = null,
            info = null,
        )
    }

    fun updatePassword(value: String) {
        _state.value = _state.value.copy(
            password = value,
            error = null,
            info = null,
        )
    }

    fun updateConfirmPassword(value: String) {
        _state.value = _state.value.copy(
            confirmPassword = value,
            error = null,
            info = null,
        )
    }

    fun continueAsGuest(onSuccess: () -> Unit) {
        if (_state.value.loading) return
        runCatching { secureTokenStore.clear() }
        _state.value = _state.value.copy(error = null, info = null)
        onSuccess()
    }

    fun submitAuth(onSuccess: () -> Unit) {
        if (_state.value.loading) return

        val current = _state.value
        val validationError = validateInput(current)
        if (validationError != null) {
            _state.value = current.copy(error = validationError, info = null)
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = true,
                error = null,
                info = null,
                attempts = _state.value.attempts + 1,
            )

            val apiKey = BuildConfig.FIREBASE_WEB_API_KEY.trim()
            if (apiKey.isBlank()) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Firebase API key missing",
                    info = null,
                )
                return@launch
            }

            val request = FirebaseEmailAuthRequestDto(
                email = current.email.trim(),
                password = current.password,
            )

            val token = fetchTokenForMode(
                mode = current.mode,
                apiKey = apiKey,
                request = request,
            )
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = friendlyNetworkError(error),
                        info = null,
                    )
                    runCatching { secureTokenStore.clear() }
                }
                .getOrNull() ?: return@launch

            secureTokenStore.saveIdToken(token, current.email.trim())
            when (val result = verifyBackendAccess()) {
                is BackendAuthResult.Success -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = null,
                        info = if (current.mode == AuthMode.SignUp) {
                            "Account created successfully."
                        } else {
                            null
                        },
                    )
                    onSuccess()
                }

                is BackendAuthResult.InvalidToken -> {
                    runCatching { secureTokenStore.clear() }
                    _state.value = _state.value.copy(
                        loading = false,
                        error = result.message,
                        info = null,
                    )
                }

                is BackendAuthResult.NetworkFailure -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = null,
                        info = if (current.mode == AuthMode.SignUp) {
                            "Account created. Backend is slow right now, live services will sync shortly."
                        } else {
                            "Logged in. Backend is slow right now, live services will sync shortly."
                        },
                    )
                    onSuccess()
                }
            }
        }
    }

    private suspend fun fetchTokenForMode(
        mode: AuthMode,
        apiKey: String,
        request: FirebaseEmailAuthRequestDto,
    ): Result<String> {
        var lastError: Throwable? = null
        repeat(FIREBASE_RETRY_COUNT) { attempt ->
            runCatching {
                val response = when (mode) {
                    AuthMode.Login -> firebaseIdentityApi.signInWithEmail(apiKey, request)
                    AuthMode.SignUp -> firebaseIdentityApi.signUpWithEmail(apiKey, request)
                }
                if (!response.isSuccessful) {
                    val body = response.errorBody()?.string().orEmpty()
                    val code = extractFirebaseErrorCode(body)
                    throw IllegalStateException(friendlyAuthError(code, response.code(), mode))
                }
                val token = response.body()?.idToken.orEmpty()
                if (token.isBlank()) {
                    throw IllegalStateException("Authentication failed [empty token]")
                }
                return Result.success(token)
            }.onFailure { error ->
                lastError = error
                if (!isRetriableNetworkError(error)) {
                    return Result.failure(error)
                }
            }

            if (attempt < FIREBASE_RETRY_COUNT - 1 && attempt < FIREBASE_RETRY_DELAYS_MS.size) {
                delay(FIREBASE_RETRY_DELAYS_MS[attempt])
            }
        }

        return Result.failure(lastError ?: IllegalStateException("Authentication failed"))
    }

    private suspend fun verifyBackendAccess(): BackendAuthResult {
        return runCatching { backendApi.aggregate("seg_0001") }
            .fold(
                onSuccess = { response ->
                    when (response.code()) {
                        401 -> BackendAuthResult.InvalidToken("Invalid token")
                        403 -> BackendAuthResult.InvalidToken("Token missing required permissions")
                        in 500..599 -> BackendAuthResult.NetworkFailure(
                            IllegalStateException("Backend unavailable [HTTP ${response.code()}]")
                        )
                        else -> BackendAuthResult.Success
                    }
                },
                onFailure = { error ->
                    BackendAuthResult.NetworkFailure(error)
                },
            )
    }

    private fun extractFirebaseErrorCode(raw: String): String {
        val p = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"")
        val m = p.matcher(raw)
        if (!m.find()) return ""
        return m.group(1)
            ?.substringBefore(" ")
            ?.substringBefore(":")
            .orEmpty()
    }

    private fun friendlyAuthError(code: String, httpCode: Int, mode: AuthMode): String {
        return when (code) {
            "CONFIGURATION_NOT_FOUND", "OPERATION_NOT_ALLOWED" -> "Cloud auth not configured. Enable Firebase Authentication with Email/Password sign-in."
            "INVALID_API_KEY", "API_KEY_INVALID" ->
                "Firebase API key invalid. Check google-services.json configuration."
            "PROJECT_NOT_FOUND" ->
                "Firebase project not found for this app configuration."
            "EMAIL_EXISTS" ->
                "This email is already registered. Please login instead."
            "EMAIL_NOT_FOUND", "INVALID_PASSWORD", "INVALID_LOGIN_CREDENTIALS", "USER_DISABLED" ->
                "Invalid email or password."
            "WEAK_PASSWORD" ->
                "Password is too weak. Use at least 6 characters."
            else ->
                "${if (mode == AuthMode.SignUp) "Sign-up" else "Login"} failed [HTTP $httpCode${if (code.isNotBlank()) " - $code" else ""}]"
        }
    }

    private fun validateInput(state: LoginUiState): String? {
        val email = state.email.trim()
        if (email.isBlank()) return "Email is required."
        if (!EMAIL_REGEX.matcher(email).matches()) return "Enter a valid email address."
        if (state.password.length < MIN_PASSWORD_LENGTH) return "Password must be at least 6 characters."
        if (state.mode == AuthMode.SignUp && state.password != state.confirmPassword) {
            return "Password and confirmation do not match."
        }
        return null
    }

    private fun friendlyNetworkError(error: Throwable): String {
        return when (error) {
            is UnknownHostException ->
                "Network/DNS unavailable. Check internet connection and DNS, then retry."
            is SocketTimeoutException, is ConnectException ->
                "Network timeout while connecting to cloud services. Please retry."
            is IOException ->
                "Unable to connect to cloud services. Check internet and retry."
            is IllegalStateException -> {
                val message = error.message.orEmpty()
                if (message.contains("Request failed after retries") || message.contains("Backend unavailable")) {
                    "Backend is temporarily unreachable. Retry in a few seconds."
                } else {
                    message.ifBlank { "Unable to connect" }
                }
            }
            else ->
                error.message ?: "Unable to connect"
        }
    }

    private fun isRetriableNetworkError(error: Throwable): Boolean {
        return when (error) {
            is UnknownHostException,
            is SocketTimeoutException,
            is ConnectException,
            is IOException -> true
            else -> false
        }
    }

    private sealed interface BackendAuthResult {
        data object Success : BackendAuthResult
        data class InvalidToken(val message: String) : BackendAuthResult
        data class NetworkFailure(val error: Throwable) : BackendAuthResult
    }

    private companion object {
        const val FIREBASE_RETRY_COUNT = 4
        val FIREBASE_RETRY_DELAYS_MS = longArrayOf(700L, 1_500L, 3_000L)
        const val MIN_PASSWORD_LENGTH = 6
        val EMAIL_REGEX: Pattern = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\$", Pattern.CASE_INSENSITIVE)
    }
}

enum class AuthMode {
    Login,
    SignUp,
}

data class LoginUiState(
    val mode: AuthMode = AuthMode.Login,
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val attempts: Int = 0,
)

