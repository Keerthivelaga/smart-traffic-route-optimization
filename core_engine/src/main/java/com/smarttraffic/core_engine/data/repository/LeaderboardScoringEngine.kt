package com.smarttraffic.core_engine.data.repository

import com.smarttraffic.core_engine.data.local.LeaderboardDao
import com.smarttraffic.core_engine.data.local.LeaderboardEntity
import com.smarttraffic.core_engine.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class LeaderboardScoringEngine @Inject constructor(
    private val leaderboardDao: LeaderboardDao,
    @IoDispatcher
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()

    suspend fun ensureSeeded() = withContext(io) {
        lock.withLock {
            ensureSeededLocked()
        }
    }

    suspend fun recordGpsContribution(
        userId: String,
        accepted: Int,
        rejected: Int,
        anomalies: Int,
        avgAccuracyMeters: Float,
    ) = withContext(io) {
        lock.withLock {
            ensureSeededLocked()
            val now = System.currentTimeMillis()
            val mutable = leaderboardDao.getAll().associateBy { it.userId }.toMutableMap()
            val current = mutable[userId] ?: baselineForUser(userId, now)

            val safeAccepted = accepted.coerceAtLeast(0)
            val safeRejected = rejected.coerceAtLeast(0)
            val safeAnomalies = anomalies.coerceAtLeast(0)
            val accuracyFactor = (1f - (avgAccuracyMeters.coerceAtLeast(1f) - 4f) / 30f).coerceIn(0.4f, 1.15f)
            val qualityFactor = (1f - (safeAnomalies.toFloat() / (safeAccepted + safeAnomalies + 1f))).coerceIn(0.5f, 1f)
            val scoreDelta = safeAccepted * 1.35f * accuracyFactor * qualityFactor -
                safeRejected * 0.65f -
                safeAnomalies * 1.1f
            val trustDelta = safeAccepted * 0.0018f * accuracyFactor -
                safeRejected * 0.0012f -
                safeAnomalies * 0.0026f

            mutable[userId] = current.copy(
                score = (current.score + scoreDelta).coerceAtLeast(0f),
                trustScore = (current.trustScore + trustDelta).coerceIn(0f, 0.99f),
                gpsContributions = current.gpsContributions + safeAccepted,
                streakDays = computeStreakDays(current.streakDays, current.updatedAtEpochMs, now),
                updatedAtEpochMs = now,
            )
            leaderboardDao.upsertAll(reRank(mutable.values.toList(), now))
        }
    }

    suspend fun recordReportValidation(
        userId: String,
        severity: Int,
        status: String,
        posteriorCredibility: Float,
        consensusScore: Float,
        messageLength: Int,
    ) = withContext(io) {
        lock.withLock {
            ensureSeededLocked()
            val now = System.currentTimeMillis()
            val mutable = leaderboardDao.getAll().associateBy { it.userId }.toMutableMap()
            val current = mutable[userId] ?: baselineForUser(userId, now)

            val credibility = posteriorCredibility.coerceIn(0f, 1f)
            val consensus = consensusScore.coerceIn(0f, 1f)
            val severityWeight = severity.coerceIn(1, 5).toFloat()
            val clarityBonus = if (messageLength >= 12) 1.6f else 0.35f
            val normalizedStatus = status.lowercase()
            val scoreDelta = when {
                normalizedStatus.contains("verified") ->
                    12f + severityWeight * 3.8f + credibility * 19f + consensus * 8f + clarityBonus
                normalizedStatus.contains("pending") ->
                    6f + severityWeight * 2.2f + credibility * 10f + consensus * 4f + clarityBonus * 0.5f
                else ->
                    -(8f + severityWeight * 3.4f) + clarityBonus * 0.1f
            }
            val trustDelta = when {
                normalizedStatus.contains("verified") -> 0.03f + credibility * 0.05f + consensus * 0.02f
                normalizedStatus.contains("pending") -> 0.01f + credibility * 0.02f
                else -> -0.03f
            }

            mutable[userId] = current.copy(
                score = (current.score + scoreDelta).coerceAtLeast(0f),
                trustScore = (current.trustScore + trustDelta).coerceIn(0f, 0.99f),
                verifiedReports = current.verifiedReports + if (normalizedStatus.contains("verified")) 1 else 0,
                streakDays = computeStreakDays(current.streakDays, current.updatedAtEpochMs, now),
                updatedAtEpochMs = now,
            )
            leaderboardDao.upsertAll(reRank(mutable.values.toList(), now))
        }
    }

    private suspend fun ensureSeededLocked() {
        val rows = leaderboardDao.getAll()
        if (rows.isEmpty()) return

        val now = System.currentTimeMillis()
        val normalizedRows = rows.map { row -> normalizeLegacyOrSyntheticRow(row, now) }
        if (normalizedRows == rows) return

        leaderboardDao.clear()
        leaderboardDao.upsertAll(reRank(normalizedRows, now = now))
    }

    private fun normalizeLegacyOrSyntheticRow(row: LeaderboardEntity, now: Long): LeaderboardEntity {
        val normalizedRow = row.copy(
            score = row.score.coerceAtLeast(0f),
            trustScore = row.trustScore.coerceIn(0f, 0.99f),
            verifiedReports = row.verifiedReports.coerceAtLeast(0),
            gpsContributions = row.gpsContributions.coerceAtLeast(0),
            streakDays = row.streakDays.coerceAtLeast(0),
            updatedAtEpochMs = row.updatedAtEpochMs.coerceAtMost(now),
        )
        if (!normalizedRow.isLikelySimulated()) {
            return normalizedRow
        }

        return normalizedRow.copy(
            score = normalizedRow.score.coerceAtMost(SIMULATED_SCORE_CAP),
            trustScore = normalizedRow.trustScore.coerceAtMost(SIMULATED_TRUST_CAP),
            verifiedReports = normalizedRow.verifiedReports.coerceAtMost(SIMULATED_VERIFIED_CAP),
            gpsContributions = normalizedRow.gpsContributions.coerceAtMost(SIMULATED_GPS_CAP),
            streakDays = normalizedRow.streakDays.coerceAtMost(SIMULATED_STREAK_CAP),
            updatedAtEpochMs = normalizedRow.updatedAtEpochMs.coerceAtMost(now - SIMULATED_STALE_OFFSET_MS),
        )
    }

    private fun LeaderboardEntity.isLikelySimulated(): Boolean {
        if (LEGACY_SEED_USER_IDS.contains(userId)) return true
        if (SIMULATED_USER_ID_PATTERNS.any { pattern -> pattern.matches(userId) }) return true
        if (score > 0f && verifiedReports == 0 && gpsContributions == 0) return true
        return false
    }

    private fun baselineForUser(userId: String, now: Long): LeaderboardEntity {
        return LeaderboardEntity(
            userId = userId,
            score = 0f,
            rank = 0,
            trustScore = 0f,
            verifiedReports = 0,
            gpsContributions = 0,
            streakDays = 0,
            updatedAtEpochMs = now,
        )
    }

    private fun reRank(rows: List<LeaderboardEntity>, now: Long): List<LeaderboardEntity> {
        val scored = rows
            .map { row ->
                val inactiveHours = ((now - row.updatedAtEpochMs).coerceAtLeast(0L) / 3_600_000L).toInt()
                val decay = max(0f, inactiveHours * 0.35f)
                row.copy(score = (row.score - decay).coerceAtLeast(0f))
            }
            .sortedWith(
                compareByDescending<LeaderboardEntity> { it.score }
                    .thenByDescending { it.trustScore }
                    .thenByDescending { it.verifiedReports }
                    .thenBy { it.userId },
            )
        return scored.mapIndexed { index, row ->
            row.copy(rank = index + 1)
        }
    }

    private fun computeStreakDays(previous: Int, previousUpdatedAtMs: Long, nowMs: Long): Int {
        if (previousUpdatedAtMs <= 0L) return max(1, previous)
        val deltaDays = ((nowMs - previousUpdatedAtMs).coerceAtLeast(0L) / DAY_MS).toInt()
        return when {
            deltaDays == 0 -> max(1, previous)
            deltaDays == 1 -> max(1, previous + 1)
            else -> 1
        }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val SIMULATED_SCORE_CAP = 25f
        const val SIMULATED_TRUST_CAP = 0.2f
        const val SIMULATED_VERIFIED_CAP = 1
        const val SIMULATED_GPS_CAP = 8
        const val SIMULATED_STREAK_CAP = 1
        const val SIMULATED_STALE_OFFSET_MS = 7 * DAY_MS
        val LEGACY_SEED_USER_IDS = setOf("u_311", "u_422", "u_390", "u_508", "u_611")
        val SIMULATED_USER_ID_PATTERNS = listOf(
            Regex("u_[0-9]{3,}"),
            Regex("mobile_user_[0-9]+"),
            Regex("test_user_[0-9]+"),
        )
    }
}
