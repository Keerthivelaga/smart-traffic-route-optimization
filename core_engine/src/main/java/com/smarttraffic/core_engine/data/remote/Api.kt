package com.smarttraffic.core_engine.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface BackendApi {
    @GET("health")
    suspend fun health(): Response<HealthDto>

    @POST("ingest/gps")
    suspend fun ingestGps(@Body payload: GpsBatchDto): Response<IngestResponseDto>

    @POST("aggregate/{segmentId}")
    suspend fun aggregate(@Path("segmentId") segmentId: String): Response<TrafficSnapshotDto>

    @POST("predict")
    suspend fun predict(@Body payload: PredictionRequestDto): Response<PredictionResponseDto>

    @POST("reports/validate")
    suspend fun validateReport(@Body payload: ReportValidationRequestDto): Response<ReportValidationResponseDto>
}

interface InferenceApi {
    @POST("predict")
    suspend fun predict(@Body payload: InferencePredictRequestDto): Response<InferencePredictResponseDto>

    @POST("predict/batch")
    suspend fun predictBatch(@Body payload: InferenceBatchRequestDto): Response<InferenceBatchResponseDto>
}

interface FirebaseIdentityApi {
    @POST("v1/accounts:signUp")
    suspend fun signUpAnonymously(
        @Query("key") apiKey: String,
        @Body payload: FirebaseAnonymousAuthRequestDto = FirebaseAnonymousAuthRequestDto(),
    ): Response<FirebaseAuthResponseDto>

    @POST("v1/accounts:signUp")
    suspend fun signUpWithEmail(
        @Query("key") apiKey: String,
        @Body payload: FirebaseEmailAuthRequestDto,
    ): Response<FirebaseAuthResponseDto>

    @POST("v1/accounts:signInWithPassword")
    suspend fun signInWithEmail(
        @Query("key") apiKey: String,
        @Body payload: FirebaseEmailAuthRequestDto,
    ): Response<FirebaseAuthResponseDto>
}

interface MapsDirectionsApi {
    @GET("maps/api/directions/json")
    suspend fun getDirections(
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("mode") mode: String = "driving",
        @Query("alternatives") alternatives: Boolean = true,
        @Query("departure_time") departureTime: String = "now",
        @Query("traffic_model") trafficModel: String,
        @Query("avoid") avoid: String? = null,
        @Query("key") apiKey: String,
    ): Response<DirectionsResponseDto>
}

interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun getCurrentWeather(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "temperature_2m,precipitation,precipitation_probability,weather_code,wind_speed_10m",
        @Query("hourly") hourly: String = "temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m",
        @Query("forecast_days") forecastDays: Int = 1,
        @Query("timezone") timezone: String = "auto",
    ): Response<OpenMeteoForecastDto>
}

@JsonClass(generateAdapter = true)
data class HealthDto(
    @Json(name = "status") val status: String,
)

@JsonClass(generateAdapter = true)
data class GpsBatchDto(
    @Json(name = "user_id") val userId: String,
    @Json(name = "points") val points: List<GpsPointDto>,
    @Json(name = "source") val source: String,
)

@JsonClass(generateAdapter = true)
data class GpsPointDto(
    @Json(name = "timestamp") val timestamp: String,
    @Json(name = "latitude") val latitude: Double,
    @Json(name = "longitude") val longitude: Double,
    @Json(name = "speed_kph") val speedKph: Float,
    @Json(name = "heading_deg") val headingDeg: Float,
    @Json(name = "accuracy_m") val accuracyM: Float,
)

@JsonClass(generateAdapter = true)
data class IngestResponseDto(
    @Json(name = "accepted") val accepted: Int,
    @Json(name = "rejected") val rejected: Int,
    @Json(name = "anomalies") val anomalies: Int,
    @Json(name = "backpressure_drops") val backpressureDrops: Int,
)

@JsonClass(generateAdapter = true)
data class TrafficSnapshotDto(
    @Json(name = "segment_id") val segmentId: String,
    @Json(name = "congestion_score") val congestionScore: Float,
    @Json(name = "confidence") val confidence: Float,
    @Json(name = "avg_speed_kph") val avgSpeedKph: Float,
    @Json(name = "anomaly_score") val anomalyScore: Float,
    @Json(name = "window_end") val windowEnd: String,
)

@JsonClass(generateAdapter = true)
data class PredictionRequestDto(
    @Json(name = "cache_key") val cacheKey: String,
    @Json(name = "segment_id") val segmentId: String,
    @Json(name = "horizon_minutes") val horizonMinutes: Int,
    @Json(name = "features") val features: List<Float>,
)

@JsonClass(generateAdapter = true)
data class PredictionResponseDto(
    @Json(name = "prediction") val prediction: Float,
    @Json(name = "latency_ms") val latencyMs: Double,
    @Json(name = "cache_key") val cacheKey: String,
)

@JsonClass(generateAdapter = true)
data class ReportVoteDto(
    @Json(name = "user_id") val userId: String,
    @Json(name = "support") val support: Boolean,
    @Json(name = "trust_score") val trustScore: Float,
    @Json(name = "reputation") val reputation: Float,
    @Json(name = "timestamp") val timestamp: String,
)

@JsonClass(generateAdapter = true)
data class ReportValidationRequestDto(
    @Json(name = "report_id") val reportId: String,
    @Json(name = "segment_id") val segmentId: String,
    @Json(name = "votes") val votes: List<ReportVoteDto>,
)

@JsonClass(generateAdapter = true)
data class ReportValidationResponseDto(
    @Json(name = "status") val status: String,
    @Json(name = "posterior_credibility") val posteriorCredibility: Float,
    @Json(name = "consensus_score") val consensusScore: Float,
)

@JsonClass(generateAdapter = true)
data class InferencePredictRequestDto(
    @Json(name = "cache_key") val cacheKey: String,
    @Json(name = "x_seq") val sequence: List<List<Float>>,
    @Json(name = "node_idx") val nodeIdx: Int,
)

@JsonClass(generateAdapter = true)
data class InferencePredictResponseDto(
    @Json(name = "prediction") val prediction: Float,
    @Json(name = "fallback") val fallback: Float,
)

@JsonClass(generateAdapter = true)
data class InferenceBatchRequestDto(
    @Json(name = "items") val items: List<InferencePredictRequestDto>,
)

@JsonClass(generateAdapter = true)
data class InferenceBatchResponseDto(
    @Json(name = "items") val items: List<InferencePredictResponseDto>,
)

@JsonClass(generateAdapter = true)
data class FirebaseAnonymousAuthRequestDto(
    @Json(name = "returnSecureToken") val returnSecureToken: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class FirebaseEmailAuthRequestDto(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String,
    @Json(name = "returnSecureToken") val returnSecureToken: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class FirebaseAuthResponseDto(
    @Json(name = "idToken") val idToken: String,
    @Json(name = "refreshToken") val refreshToken: String?,
    @Json(name = "expiresIn") val expiresIn: String?,
    @Json(name = "localId") val localId: String?,
)

@JsonClass(generateAdapter = true)
data class DirectionsResponseDto(
    @Json(name = "status") val status: String,
    @Json(name = "error_message") val errorMessage: String? = null,
    @Json(name = "routes") val routes: List<DirectionsRouteDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DirectionsRouteDto(
    @Json(name = "summary") val summary: String? = null,
    @Json(name = "overview_polyline") val overviewPolyline: DirectionsPolylineDto? = null,
    @Json(name = "legs") val legs: List<DirectionsLegDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DirectionsLegDto(
    @Json(name = "distance") val distance: DirectionsMetricDto? = null,
    @Json(name = "duration") val duration: DirectionsMetricDto? = null,
    @Json(name = "duration_in_traffic") val durationInTraffic: DirectionsMetricDto? = null,
    @Json(name = "steps") val steps: List<DirectionsStepDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DirectionsMetricDto(
    @Json(name = "value") val value: Int? = null,
    @Json(name = "text") val text: String? = null,
)

@JsonClass(generateAdapter = true)
data class DirectionsPolylineDto(
    @Json(name = "points") val points: String? = null,
)

@JsonClass(generateAdapter = true)
data class DirectionsStepDto(
    @Json(name = "html_instructions") val htmlInstructions: String? = null,
    @Json(name = "distance") val distance: DirectionsMetricDto? = null,
    @Json(name = "duration") val duration: DirectionsMetricDto? = null,
    @Json(name = "start_location") val startLocation: DirectionsLocationDto? = null,
    @Json(name = "end_location") val endLocation: DirectionsLocationDto? = null,
    @Json(name = "polyline") val polyline: DirectionsPolylineDto? = null,
)

@JsonClass(generateAdapter = true)
data class DirectionsLocationDto(
    @Json(name = "lat") val lat: Double? = null,
    @Json(name = "lng") val lng: Double? = null,
)

@JsonClass(generateAdapter = true)
data class OpenMeteoForecastDto(
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "timezone") val timezone: String? = null,
    @Json(name = "current") val current: OpenMeteoCurrentDto? = null,
    @Json(name = "hourly") val hourly: OpenMeteoHourlyDto? = null,
    @Json(name = "error") val error: Boolean? = null,
    @Json(name = "reason") val reason: String? = null,
)

@JsonClass(generateAdapter = true)
data class OpenMeteoCurrentDto(
    @Json(name = "time") val time: String? = null,
    @Json(name = "temperature_2m") val temperature2m: Double? = null,
    @Json(name = "precipitation") val precipitation: Double? = null,
    @Json(name = "precipitation_probability") val precipitationProbability: Double? = null,
    @Json(name = "weather_code") val weatherCode: Int? = null,
    @Json(name = "wind_speed_10m") val windSpeed10m: Double? = null,
)

@JsonClass(generateAdapter = true)
data class OpenMeteoHourlyDto(
    @Json(name = "time") val time: List<String>? = null,
    @Json(name = "temperature_2m") val temperature2m: List<Double>? = null,
    @Json(name = "precipitation") val precipitation: List<Double>? = null,
    @Json(name = "precipitation_probability") val precipitationProbability: List<Double>? = null,
    @Json(name = "weather_code") val weatherCode: List<Int>? = null,
    @Json(name = "wind_speed_10m") val windSpeed10m: List<Double>? = null,
)

