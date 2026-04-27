package com.smarttraffic.app

import com.smarttraffic.app.session.NavigationSessionStore
import com.smarttraffic.app.ui.screens.reporting.ReportingViewModel
import com.smarttraffic.core_engine.data.location.DeviceLocationResolver
import com.smarttraffic.core_engine.domain.usecase.ReportIncidentUseCase
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var useCase: ReportIncidentUseCase
    private lateinit var locationResolver: DeviceLocationResolver
    private lateinit var sessionStore: NavigationSessionStore
    private lateinit var secureTokenStore: SecureTokenStore

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        useCase = mockk()
        locationResolver = mockk()
        sessionStore = NavigationSessionStore()
        secureTokenStore = mockk()
        coEvery { locationResolver.resolveCurrentLocationLatLng() } returns Result.success("28.613900,77.209000")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun submit_success_updatesResult() = runTest {
        every { secureTokenStore.currentUserId() } returns "user_123"
        coEvery { useCase.invoke(any()) } returns Result.success(Unit)
        val vm = ReportingViewModel(useCase, locationResolver, sessionStore, secureTokenStore)

        vm.updateType("hazard")
        vm.updateMessage("Debris on lane")
        vm.submit()
        advanceUntilIdle()

        assertEquals(true, vm.state.value.result?.contains("Report submitted from") == true)
    }

    @Test
    fun guest_user_is_blocked_from_submission() = runTest {
        every { secureTokenStore.currentUserId() } returns null
        val vm = ReportingViewModel(useCase, locationResolver, sessionStore, secureTokenStore)

        vm.submit()
        advanceUntilIdle()

        assertEquals(false, vm.state.value.isAuthenticated)
        assertEquals("Please login to report incidents.", vm.state.value.result)
    }
}

