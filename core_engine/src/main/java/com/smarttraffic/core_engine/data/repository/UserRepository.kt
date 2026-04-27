package com.smarttraffic.core_engine.data.repository

import com.smarttraffic.core_engine.data.local.LeaderboardDao
import com.smarttraffic.core_engine.data.local.LeaderboardEntity
import com.smarttraffic.core_engine.data.remote.BackendApi
import com.smarttraffic.core_engine.data.remote.ReportValidationRequestDto
import com.smarttraffic.core_engine.data.remote.ReportVoteDto
import com.smarttraffic.core_engine.di.IoDispatcher
import com.smarttraffic.core_engine.domain.model.IncidentReport
import com.smarttraffic.core_engine.domain.model.LeaderboardEntry
import com.smarttraffic.core_engine.domain.model.UserProfile
import com.smarttraffic.core_engine.domain.repository.UserRepository
import com.smarttraffic.core_engine.security.SecureTokenStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val backendApi: BackendApi,
    private val leaderboardDao: LeaderboardDao,
    private val leaderboardScoringEngine: LeaderboardScoringEngine,
    private val secureTokenStore: SecureTokenStore,
    @IoDispatcher
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UserRepository {

    override fun observeLeaderboard(): Flow<List<LeaderboardEntry>> {
        return flow {
            leaderboardScoringEngine.ensureSeeded()
            emitAll(leaderboardDao.observe().map { rows -> rows.map { it.toDomain() } })
        }
    }

    override suspend fun getProfile(userId: String): Result<UserProfile> = withContext(io) {
        runCatching {
            leaderboardScoringEngine.ensureSeeded()
            val currentUserId = secureTokenStore.currentUserId()
            val currentEmail = secureTokenStore.currentEmail()
            val isCurrentUser = currentUserId != null && currentUserId == userId
            val displayName = when {
                isCurrentUser && !currentEmail.isNullOrBlank() -> currentEmail
                else -> "Driver ${userId.takeLast(4)}"
            }

            val row = leaderboardDao.getByUserId(userId)
            UserProfile(
                userId = userId,
                displayName = displayName,
                trustScore = row?.trustScore?.coerceIn(0f, 1f) ?: 0f,
                reputation = row?.score?.coerceAtLeast(0f) ?: 0f,
                achievements = row?.toProfileAchievements().orEmpty(),
            )
        }
    }

    override suspend fun reportIncident(report: IncidentReport): Result<Unit> = withContext(io) {
        runCatching {
            val currentUserId = secureTokenStore.currentUserId()
                ?: throw IllegalStateException("Login required to report incidents")
            leaderboardScoringEngine.ensureSeeded()
            val reporterRow = leaderboardDao.getByUserId(currentUserId)
            val payload = ReportValidationRequestDto(
                reportId = if (report.reportId.isBlank()) UUID.randomUUID().toString() else report.reportId,
                segmentId = report.segmentId,
                votes = listOf(
                    ReportVoteDto(
                        userId = currentUserId,
                        support = true,
                        trustScore = reporterRow?.trustScore?.coerceIn(0f, 1f) ?: 0f,
                        reputation = reporterRow?.score?.coerceAtLeast(0f) ?: 0f,
                        timestamp = Instant.now().toString(),
                    )
                ),
            )
            val response = backendApi.validateReport(payload)
            val body = requireSuccessful(response) { "Incident report validation failed for ${report.segmentId}" }
            leaderboardScoringEngine.recordReportValidation(
                userId = currentUserId,
                severity = report.severity,
                status = body.status,
                posteriorCredibility = body.posteriorCredibility,
                consensusScore = body.consensusScore,
                messageLength = report.message.length,
            )
            Unit
        }
    }

    suspend fun refreshLeaderboard() = withContext(io) {
        leaderboardScoringEngine.ensureSeeded()
    }
}

private fun LeaderboardEntity.toDomain() = LeaderboardEntry(
    userId = userId,
    score = score,
    rank = rank,
    trustScore = trustScore,
    verifiedReports = verifiedReports,
    gpsContributions = gpsContributions,
    streakDays = streakDays,
    lastUpdatedEpochMs = updatedAtEpochMs,
)

private fun LeaderboardEntity.toProfileAchievements(): List<String> {
    val badges = mutableListOf<String>()
    if (verifiedReports >= 1) badges += "Verified Reporter"
    if (gpsContributions >= 25) badges += "GPS Contributor"
    if (streakDays >= 3) badges += "Consistency Streak"
    if (trustScore >= 0.85f && verifiedReports + gpsContributions >= 10) {
        badges += "High Trust Signal"
    }
    return badges
}

private inline fun <T> requireSuccessful(response: Response<T>, lazyMessage: () -> String): T {
    if (!response.isSuccessful) {
        throw IllegalStateException("${lazyMessage()} [HTTP ${response.code()}]")
    }
    return response.body() ?: throw IllegalStateException("${lazyMessage()} [empty_body]")
}
