package com.smarttraffic.core_engine

import com.smarttraffic.core_engine.data.local.LeaderboardDao
import com.smarttraffic.core_engine.data.local.LeaderboardEntity
import com.smarttraffic.core_engine.data.remote.BackendApi
import com.smarttraffic.core_engine.data.repository.LeaderboardScoringEngine
import com.smarttraffic.core_engine.data.repository.UserRepositoryImpl
import com.smarttraffic.core_engine.security.SecureTokenStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserRepositoryProfileTest {

    @Test
    fun `new user profile is unrated until contributions exist`() = runTest {
        val backendApi = mockk<BackendApi>()
        val leaderboardDao = mockk<LeaderboardDao>()
        val scoringEngine = mockk<LeaderboardScoringEngine>(relaxed = true)
        val tokenStore = mockk<SecureTokenStore>()

        coEvery { leaderboardDao.getByUserId("uid_1001") } returns null
        every { tokenStore.currentUserId() } returns "uid_1001"
        every { tokenStore.currentEmail() } returns "driver@example.com"

        val repository = UserRepositoryImpl(
            backendApi = backendApi,
            leaderboardDao = leaderboardDao,
            leaderboardScoringEngine = scoringEngine,
            secureTokenStore = tokenStore,
            io = Dispatchers.Unconfined,
        )

        val result = repository.getProfile("uid_1001")
        val profile = result.getOrThrow()

        assertEquals("uid_1001", profile.userId)
        assertEquals("driver@example.com", profile.displayName)
        assertEquals(0f, profile.trustScore)
        assertEquals(0f, profile.reputation)
        assertTrue(profile.achievements.isEmpty())
    }

    @Test
    fun `profile metrics are derived from real leaderboard row`() = runTest {
        val backendApi = mockk<BackendApi>()
        val leaderboardDao = mockk<LeaderboardDao>()
        val scoringEngine = mockk<LeaderboardScoringEngine>(relaxed = true)
        val tokenStore = mockk<SecureTokenStore>()

        every { tokenStore.currentUserId() } returns null
        every { tokenStore.currentEmail() } returns null
        coEvery { leaderboardDao.getByUserId("uid_2048") } returns LeaderboardEntity(
            userId = "uid_2048",
            score = 145f,
            rank = 7,
            trustScore = 0.88f,
            verifiedReports = 2,
            gpsContributions = 30,
            streakDays = 4,
            updatedAtEpochMs = System.currentTimeMillis(),
        )

        val repository = UserRepositoryImpl(
            backendApi = backendApi,
            leaderboardDao = leaderboardDao,
            leaderboardScoringEngine = scoringEngine,
            secureTokenStore = tokenStore,
            io = Dispatchers.Unconfined,
        )

        val result = repository.getProfile("uid_2048")
        val profile = result.getOrThrow()

        assertEquals("Driver 2048", profile.displayName)
        assertEquals(0.88f, profile.trustScore)
        assertEquals(145f, profile.reputation)
        assertTrue(profile.achievements.contains("Verified Reporter"))
        assertTrue(profile.achievements.contains("GPS Contributor"))
        assertTrue(profile.achievements.contains("Consistency Streak"))
        assertTrue(profile.achievements.contains("High Trust Signal"))
    }
}

