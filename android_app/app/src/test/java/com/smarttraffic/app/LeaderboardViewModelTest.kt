package com.smarttraffic.app

import com.smarttraffic.app.ui.screens.leaderboard.LeaderboardViewModel
import com.smarttraffic.core_engine.domain.model.LeaderboardEntry
import com.smarttraffic.core_engine.domain.usecase.ObserveLeaderboardUseCase
import com.smarttraffic.core_engine.security.SecureTokenStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LeaderboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var observeLeaderboardUseCase: ObserveLeaderboardUseCase
    private lateinit var secureTokenStore: SecureTokenStore

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        observeLeaderboardUseCase = mockk()
        secureTokenStore = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `guest user does not get myEntry even when seed row exists`() = runTest {
        every { secureTokenStore.currentUserId() } returns null
        every { observeLeaderboardUseCase.invoke() } returns flowOf(
            listOf(
                LeaderboardEntry(userId = "mobile_user_001", score = 1000f, rank = 1),
                LeaderboardEntry(userId = "u_222", score = 980f, rank = 2),
            )
        )

        val vm = LeaderboardViewModel(observeLeaderboardUseCase, secureTokenStore)
        advanceUntilIdle()

        assertEquals(false, vm.state.value.isAuthenticated)
        assertNull(vm.state.value.myEntry)
    }

    @Test
    fun `authenticated user gets myEntry from token user id`() = runTest {
        every { secureTokenStore.currentUserId() } returns "uid_abc_123"
        every { observeLeaderboardUseCase.invoke() } returns flowOf(
            listOf(
                LeaderboardEntry(userId = "u_555", score = 1000f, rank = 1),
                LeaderboardEntry(userId = "uid_abc_123", score = 955f, rank = 2),
            )
        )

        val vm = LeaderboardViewModel(observeLeaderboardUseCase, secureTokenStore)
        advanceUntilIdle()

        assertEquals(true, vm.state.value.isAuthenticated)
        assertEquals("uid_abc_123", vm.state.value.myEntry?.userId)
    }
}

