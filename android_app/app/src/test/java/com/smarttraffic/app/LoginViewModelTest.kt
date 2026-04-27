package com.smarttraffic.app

import com.smarttraffic.app.ui.screens.auth.AuthMode
import com.smarttraffic.app.ui.screens.auth.LoginViewModel
import com.smarttraffic.core_engine.data.remote.BackendApi
import com.smarttraffic.core_engine.data.remote.FirebaseAuthResponseDto
import com.smarttraffic.core_engine.data.remote.FirebaseIdentityApi
import com.smarttraffic.core_engine.data.remote.TrafficSnapshotDto
import com.smarttraffic.core_engine.security.SecureTokenStore
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var backendApi: BackendApi
    private lateinit var secureTokenStore: SecureTokenStore
    private lateinit var firebaseIdentityApi: FirebaseIdentityApi

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        backendApi = mockk()
        secureTokenStore = mockk(relaxed = true)
        firebaseIdentityApi = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `continue as guest clears token`() {
        val vm = LoginViewModel(secureTokenStore, backendApi, firebaseIdentityApi)
        var proceeded = false

        vm.continueAsGuest {
            proceeded = true
        }

        verify { secureTokenStore.clear() }
        assertTrue(proceeded)
    }

    @Test
    fun `signup persists token`() = runTest {
        coEvery { firebaseIdentityApi.signUpWithEmail(any(), any()) } returns Response.success(
            FirebaseAuthResponseDto(
                idToken = "signup_token",
                refreshToken = "r",
                expiresIn = "3600",
                localId = "u1",
            )
        )
        coEvery { backendApi.aggregate("seg_0001") } returns Response.success(
            TrafficSnapshotDto(
                segmentId = "seg_0001",
                congestionScore = 0.42f,
                confidence = 0.9f,
                avgSpeedKph = 38f,
                anomalyScore = 0.1f,
                windowEnd = "2026-01-01T00:00:00Z",
            )
        )
        val vm = LoginViewModel(secureTokenStore, backendApi, firebaseIdentityApi)
        vm.updateMode(AuthMode.SignUp)
        vm.updateEmail("signup@example.com")
        vm.updatePassword("password123")
        vm.updateConfirmPassword("password123")

        vm.submitAuth {}
        dispatcher.scheduler.advanceUntilIdle()

        verify { secureTokenStore.saveIdToken("signup_token", "signup@example.com") }
        assertTrue(vm.state.value.error == null)
    }

    @Test
    fun `invalid backend auth surfaces error`() = runTest {
        coEvery { firebaseIdentityApi.signInWithEmail(any(), any()) } returns Response.success(
            FirebaseAuthResponseDto(
                idToken = "bad_token",
                refreshToken = "r",
                expiresIn = "3600",
                localId = "u1",
            )
        )
        coEvery { backendApi.aggregate("seg_0001") } returns Response.error(
            401,
            "{}".toResponseBody("application/json".toMediaType()),
        )
        val vm = LoginViewModel(secureTokenStore, backendApi, firebaseIdentityApi)
        vm.updateEmail("user@example.com")
        vm.updatePassword("password123")

        vm.submitAuth {}
        dispatcher.scheduler.advanceUntilIdle()

        verify { secureTokenStore.clear() }
        assertTrue(vm.state.value.error == "Invalid token")
    }

    @Test
    fun `backend timeout does not block signup success`() = runTest {
        coEvery { firebaseIdentityApi.signUpWithEmail(any(), any()) } returns Response.success(
            FirebaseAuthResponseDto(
                idToken = "signup_token_timeout_backend",
                refreshToken = "r",
                expiresIn = "3600",
                localId = "u2",
            )
        )
        coEvery { backendApi.aggregate("seg_0001") } throws SocketTimeoutException("timeout")

        val vm = LoginViewModel(secureTokenStore, backendApi, firebaseIdentityApi)
        vm.updateMode(AuthMode.SignUp)
        vm.updateEmail("timeout@example.com")
        vm.updatePassword("password123")
        vm.updateConfirmPassword("password123")

        var success = false
        vm.submitAuth { success = true }
        dispatcher.scheduler.advanceUntilIdle()

        verify { secureTokenStore.saveIdToken("signup_token_timeout_backend", "timeout@example.com") }
        assertTrue(success)
        assertTrue(vm.state.value.error == null)
        assertTrue(vm.state.value.info?.contains("Backend is slow") == true)
    }
}

