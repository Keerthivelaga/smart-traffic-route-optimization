package com.smarttraffic.app

import com.smarttraffic.app.ui.screens.profile.ProfileViewModel
import com.smarttraffic.core_engine.domain.model.UserProfile
import com.smarttraffic.core_engine.domain.usecase.GetProfileUseCase
import com.smarttraffic.core_engine.security.SecureTokenStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var getProfileUseCase: GetProfileUseCase
    private lateinit var secureTokenStore: SecureTokenStore

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        getProfileUseCase = mockk()
        secureTokenStore = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `authenticated profile shows current email as display name`() = runTest {
        every { secureTokenStore.currentUserId() } returns "uid_rohan_123"
        every { secureTokenStore.currentEmail() } returns "rohan@gmail.com"
        coEvery { getProfileUseCase.invoke("uid_rohan_123") } returns Result.success(
            UserProfile(
                userId = "uid_rohan_123",
                displayName = "Driver 0123",
                trustScore = 0.8f,
                reputation = 77f,
                achievements = emptyList(),
            )
        )

        val vm = ProfileViewModel(getProfileUseCase, secureTokenStore)
        advanceUntilIdle()

        assertTrue(vm.state.value.isAuthenticated)
        assertEquals("rohan@gmail.com", vm.state.value.email)
        assertEquals("rohan@gmail.com", vm.state.value.profile.displayName)
        assertEquals("uid_rohan_123", vm.state.value.profile.userId)
    }

    @Test
    fun `guest profile shows login required state`() = runTest {
        every { secureTokenStore.currentUserId() } returns null
        every { secureTokenStore.currentEmail() } returns null

        val vm = ProfileViewModel(getProfileUseCase, secureTokenStore)
        advanceUntilIdle()

        assertFalse(vm.state.value.isAuthenticated)
        assertEquals("guest", vm.state.value.profile.userId)
        assertEquals("Guest User", vm.state.value.profile.displayName)
        assertEquals("Login to view your account details.", vm.state.value.error)
    }
}
