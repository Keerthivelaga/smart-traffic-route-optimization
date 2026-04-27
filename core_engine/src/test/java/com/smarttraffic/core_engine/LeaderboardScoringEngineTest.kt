package com.smarttraffic.core_engine

import com.smarttraffic.core_engine.data.local.LeaderboardDao
import com.smarttraffic.core_engine.data.local.LeaderboardEntity
import com.smarttraffic.core_engine.data.repository.LeaderboardScoringEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LeaderboardScoringEngineTest {

    @Test
    fun `ensureSeeded normalizes simulated rows so real users can move up quickly`() = runTest {
        val now = System.currentTimeMillis()
        val leaderboardDao = mockk<LeaderboardDao>()
        val capturedRows = slot<List<LeaderboardEntity>>()

        coEvery { leaderboardDao.getAll() } returns listOf(
            LeaderboardEntity(
                userId = "u_311",
                score = 980f,
                rank = 1,
                trustScore = 0.74f,
                verifiedReports = 3,
                gpsContributions = 120,
                streakDays = 2,
                updatedAtEpochMs = now,
            ),
            LeaderboardEntity(
                userId = "uid_real_2048",
                score = 44f,
                rank = 2,
                trustScore = 0.66f,
                verifiedReports = 1,
                gpsContributions = 5,
                streakDays = 1,
                updatedAtEpochMs = now,
            ),
        )
        coEvery { leaderboardDao.clear() } just runs
        coEvery { leaderboardDao.upsertAll(capture(capturedRows)) } just runs

        val engine = LeaderboardScoringEngine(
            leaderboardDao = leaderboardDao,
            io = Dispatchers.Unconfined,
        )

        engine.ensureSeeded()

        coVerify(exactly = 1) { leaderboardDao.clear() }
        coVerify(exactly = 1) { leaderboardDao.upsertAll(any()) }

        val normalized = capturedRows.captured
        val simulated = normalized.first { it.userId == "u_311" }
        val real = normalized.first { it.userId == "uid_real_2048" }

        assertTrue(simulated.score <= 25f)
        assertTrue(simulated.trustScore <= 0.2f)
        assertTrue(simulated.gpsContributions <= 8)
        assertEquals(1, real.rank)
        assertEquals(2, simulated.rank)
    }

    @Test
    fun `ensureSeeded does nothing when leaderboard is empty`() = runTest {
        val leaderboardDao = mockk<LeaderboardDao>()
        coEvery { leaderboardDao.getAll() } returns emptyList()

        val engine = LeaderboardScoringEngine(
            leaderboardDao = leaderboardDao,
            io = Dispatchers.Unconfined,
        )

        engine.ensureSeeded()

        coVerify(exactly = 0) { leaderboardDao.clear() }
        coVerify(exactly = 0) { leaderboardDao.upsertAll(any()) }
    }
}
