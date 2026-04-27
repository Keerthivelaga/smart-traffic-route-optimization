package com.smarttraffic.core_engine.domain.model

import kotlinx.coroutines.flow.Flow

enum class RoutingMode {
    FASTEST,
    FUEL_EFFICIENT,
    LOW_TRAFFIC,
    SCENIC,
}

data class TrafficSnapshot(
    val segmentId: String,
    val congestionScore: Float,
    val confidence: Float,
    val avgSpeedKph: Float,
    val anomalyScore: Float,
    val timestampIso: String,
)

data class RouteOption(
    val id: String,
    val title: String,
    val etaMinutes: Int,
    val distanceKm: Float,
    val confidence: Float,
    val mode: RoutingMode,
    val weather: RouteWeatherSummary? = null,
    val weatherRiskScore: Float = 0f,
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

data class NavigationRoute(
    val routeId: String,
    val title: String,
    val etaMinutes: Int,
    val distanceKm: Float,
    val polyline: List<GeoPoint>,
    val instructions: List<VoiceInstruction>,
    val steps: List<RouteStep>,
    val congestionScore: Float,
    val weather: RouteWeatherSummary? = null,
)

enum class WeatherSeverity {
    LOW,
    MODERATE,
    HIGH,
    SEVERE,
}

data class RouteWeatherSummary(
    val summary: String,
    val severity: WeatherSeverity,
    val riskScore: Float,
    val checkpoints: List<RouteWeatherCheckpoint>,
    val averageTemperatureC: Float? = null,
    val maxPrecipitationProbabilityPct: Int? = null,
    val maxWindSpeedKph: Float? = null,
)

data class RouteWeatherCheckpoint(
    val label: String,
    val condition: String,
    val weatherCode: Int? = null,
    val observationTimeIso: String? = null,
    val temperatureC: Float? = null,
    val precipitationMm: Float? = null,
    val precipitationProbabilityPct: Int? = null,
    val windSpeedKph: Float? = null,
)

data class RouteStep(
    val instruction: VoiceInstruction,
    val start: GeoPoint?,
    val end: GeoPoint?,
    val polyline: List<GeoPoint>,
    val congestionScore: Float,
)

data class PredictionInput(
    val cacheKey: String,
    val segmentId: String,
    val horizonMinutes: Int,
    val features: List<Float>,
)

data class PredictionResult(
    val value: Float,
    val fallback: Boolean,
)

data class IncidentReport(
    val reportId: String,
    val segmentId: String,
    val type: String,
    val severity: Int,
    val latitude: Double,
    val longitude: Double,
    val message: String,
)

data class LeaderboardEntry(
    val userId: String,
    val score: Float,
    val rank: Int,
    val trustScore: Float = 0.5f,
    val verifiedReports: Int = 0,
    val gpsContributions: Int = 0,
    val streakDays: Int = 0,
    val lastUpdatedEpochMs: Long = 0L,
)

data class UserProfile(
    val userId: String,
    val displayName: String,
    val trustScore: Float,
    val reputation: Float,
    val achievements: List<String>,
)

data class VoiceInstruction(
    val text: String,
    val distanceMeters: Int,
)

