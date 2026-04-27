package com.smarttraffic.core_engine

import com.smarttraffic.core_engine.data.local.TrafficDao
import com.smarttraffic.core_engine.data.remote.BackendApi
import com.smarttraffic.core_engine.data.remote.InferenceApi
import com.smarttraffic.core_engine.data.remote.InferencePredictResponseDto
import com.smarttraffic.core_engine.data.repository.LeaderboardScoringEngine
import com.smarttraffic.core_engine.data.repository.TrafficRepositoryImpl
import com.smarttraffic.core_engine.domain.model.PredictionInput
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficRepositoryNetworkFailureTest {

    @Test
    fun fallsBack_toInference_whenBackendPredictFails() = runTest {
        val backendApi = mockk<BackendApi>()
        val inferenceApi = mockk<InferenceApi>()
        val trafficDao = mockk<TrafficDao>(relaxed = true)
        val leaderboardScoringEngine = mockk<LeaderboardScoringEngine>(relaxed = true)

        coEvery { backendApi.predict(any()) } returns Response.error(500, "x".toResponseBody())
        coEvery { inferenceApi.predict(any()) } returns Response.success(
            InferencePredictResponseDto(prediction = 0.77f, fallback = 0f)
        )
        coEvery { trafficDao.observe(any()) } returns emptyFlow()

        val repository = TrafficRepositoryImpl(
            backendApi,
            inferenceApi,
            trafficDao,
            leaderboardScoringEngine,
            Dispatchers.Unconfined,
        )

        val result = repository.predict(
            PredictionInput(
                cacheKey = "c1",
                segmentId = "seg_0001",
                horizonMinutes = 15,
                features = List(16) { 0.1f },
            )
        )

        assertTrue(result.isSuccess)
        assertTrue((result.getOrNull()?.value ?: 0f) > 0f)
    }
}
