package com.smarttraffic.core_engine

import com.smarttraffic.core_engine.data.local.RouteDao
import com.smarttraffic.core_engine.data.location.DeviceLocationResolver
import com.smarttraffic.core_engine.data.remote.DirectionsLegDto
import com.smarttraffic.core_engine.data.remote.DirectionsLocationDto
import com.smarttraffic.core_engine.data.remote.DirectionsMetricDto
import com.smarttraffic.core_engine.data.remote.DirectionsPolylineDto
import com.smarttraffic.core_engine.data.remote.DirectionsResponseDto
import com.smarttraffic.core_engine.data.remote.DirectionsRouteDto
import com.smarttraffic.core_engine.data.remote.DirectionsStepDto
import com.smarttraffic.core_engine.data.remote.OpenMeteoApi
import com.smarttraffic.core_engine.data.remote.OpenMeteoCurrentDto
import com.smarttraffic.core_engine.data.remote.OpenMeteoForecastDto
import com.smarttraffic.core_engine.data.remote.OpenMeteoHourlyDto
import com.smarttraffic.core_engine.data.remote.RouteDirectionsProvider
import com.smarttraffic.core_engine.data.repository.RouteRepositoryImpl
import com.smarttraffic.core_engine.domain.model.RoutingMode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class RouteRepositoryWeatherTest {

    @Test
    fun refreshRoutes_enrichesRoutesWithWeatherSummary() = kotlinx.coroutines.test.runTest {
        val routeDao = mockk<RouteDao>(relaxed = true)
        val routeDirectionsProvider = mockk<RouteDirectionsProvider>()
        val openMeteoApi = mockk<OpenMeteoApi>()
        val locationResolver = mockk<DeviceLocationResolver>()

        coEvery {
            routeDirectionsProvider.fetchDirections(any())
        } returns Response.success(
            DirectionsResponseDto(
                status = "OK",
                routes = listOf(
                    DirectionsRouteDto(
                        summary = "Ring Road",
                        overviewPolyline = DirectionsPolylineDto(points = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"),
                        legs = listOf(
                            DirectionsLegDto(
                                distance = DirectionsMetricDto(value = 12000, text = "12 km"),
                                duration = DirectionsMetricDto(value = 1200, text = "20 mins"),
                                durationInTraffic = DirectionsMetricDto(value = 1560, text = "26 mins"),
                                steps = listOf(
                                    DirectionsStepDto(
                                        htmlInstructions = "Head north",
                                        distance = DirectionsMetricDto(value = 1200, text = "1.2 km"),
                                        duration = DirectionsMetricDto(value = 180, text = "3 mins"),
                                        startLocation = DirectionsLocationDto(lat = 38.0, lng = -122.0),
                                        endLocation = DirectionsLocationDto(lat = 38.02, lng = -122.03),
                                        polyline = DirectionsPolylineDto(points = "_p~iF~ps|U_ulLnnqC"),
                                    )
                                ),
                            )
                        ),
                    )
                ),
            )
        )

        coEvery {
            openMeteoApi.getCurrentWeather(
                latitude = any(),
                longitude = any(),
                current = any(),
                hourly = any(),
                forecastDays = any(),
                timezone = any(),
            )
        } returns Response.success(
            OpenMeteoForecastDto(
                current = OpenMeteoCurrentDto(
                    time = "2026-02-20T18:00",
                    temperature2m = 21.5,
                    precipitation = 2.1,
                    precipitationProbability = 84.0,
                    weatherCode = 65,
                    windSpeed10m = 32.0,
                )
            )
        )

        val repository = RouteRepositoryImpl(
            routeDao = routeDao,
            routeDirectionsProvider = routeDirectionsProvider,
            openMeteoApi = openMeteoApi,
            locationResolver = locationResolver,
            io = Dispatchers.Unconfined,
        )

        val refresh = repository.refreshRoutes(
            origin = "Sector 21",
            destination = "Connaught Place",
            mode = RoutingMode.FASTEST,
        )
        assertTrue(refresh.isSuccess)
        val option = refresh.getOrNull()?.firstOrNull()
        assertTrue(option != null)
        assertTrue(option?.weather != null)
        assertTrue((option?.weatherRiskScore ?: 0f) > 0.4f)

        val nav = repository.fetchNavigationRoute(
            origin = "Sector 21",
            destination = "Connaught Place",
            mode = RoutingMode.FASTEST,
            selectedRouteId = option?.id,
        )
        assertTrue(nav.isSuccess)
        assertTrue(nav.getOrNull()?.weather != null)
    }

    @Test
    fun refreshRoutes_usesEtaAwareForecastSoWeatherDiffersAcrossRoutes() = kotlinx.coroutines.test.runTest {
        val routeDao = mockk<RouteDao>(relaxed = true)
        val routeDirectionsProvider = mockk<RouteDirectionsProvider>()
        val openMeteoApi = mockk<OpenMeteoApi>()
        val locationResolver = mockk<DeviceLocationResolver>()

        val sharedPolyline = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        coEvery {
            routeDirectionsProvider.fetchDirections(any())
        } returns Response.success(
            DirectionsResponseDto(
                status = "OK",
                routes = listOf(
                    DirectionsRouteDto(
                        summary = "Ring Road",
                        overviewPolyline = DirectionsPolylineDto(points = sharedPolyline),
                        legs = listOf(
                            DirectionsLegDto(
                                distance = DirectionsMetricDto(value = 12000, text = "12 km"),
                                duration = DirectionsMetricDto(value = 840, text = "14 mins"),
                                durationInTraffic = DirectionsMetricDto(value = 900, text = "15 mins"),
                                steps = listOf(
                                    DirectionsStepDto(
                                        htmlInstructions = "Head north",
                                        distance = DirectionsMetricDto(value = 1200, text = "1.2 km"),
                                        duration = DirectionsMetricDto(value = 180, text = "3 mins"),
                                        startLocation = DirectionsLocationDto(lat = 38.0, lng = -122.0),
                                        endLocation = DirectionsLocationDto(lat = 38.02, lng = -122.03),
                                        polyline = DirectionsPolylineDto(points = "_p~iF~ps|U_ulLnnqC"),
                                    )
                                ),
                            )
                        ),
                    ),
                    DirectionsRouteDto(
                        summary = "Outer Bypass",
                        overviewPolyline = DirectionsPolylineDto(points = sharedPolyline),
                        legs = listOf(
                            DirectionsLegDto(
                                distance = DirectionsMetricDto(value = 16000, text = "16 km"),
                                duration = DirectionsMetricDto(value = 3600, text = "60 mins"),
                                durationInTraffic = DirectionsMetricDto(value = 9000, text = "150 mins"),
                                steps = listOf(
                                    DirectionsStepDto(
                                        htmlInstructions = "Continue on bypass",
                                        distance = DirectionsMetricDto(value = 2100, text = "2.1 km"),
                                        duration = DirectionsMetricDto(value = 720, text = "12 mins"),
                                        startLocation = DirectionsLocationDto(lat = 38.0, lng = -122.0),
                                        endLocation = DirectionsLocationDto(lat = 38.04, lng = -122.06),
                                        polyline = DirectionsPolylineDto(points = "_p~iF~ps|U_ulLnnqC"),
                                    )
                                ),
                            )
                        ),
                    ),
                ),
            )
        )

        val nowHourUtc = LocalDateTime.now(ZoneOffset.UTC)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val times = (0..6).map { hour -> nowHourUtc.plusHours(hour.toLong()).format(formatter) }
        coEvery {
            openMeteoApi.getCurrentWeather(
                latitude = any(),
                longitude = any(),
                current = any(),
                hourly = any(),
                forecastDays = any(),
                timezone = any(),
            )
        } returns Response.success(
            OpenMeteoForecastDto(
                timezone = "UTC",
                current = OpenMeteoCurrentDto(
                    time = times.first(),
                    temperature2m = 23.0,
                    precipitation = 0.0,
                    precipitationProbability = 8.0,
                    weatherCode = 1,
                    windSpeed10m = 12.0,
                ),
                hourly = OpenMeteoHourlyDto(
                    time = times,
                    temperature2m = listOf(23.0, 22.0, 19.0, 17.0, 16.0, 16.0, 15.0),
                    precipitation = listOf(0.0, 0.1, 0.6, 2.8, 5.2, 1.2, 0.4),
                    precipitationProbability = listOf(5.0, 12.0, 35.0, 72.0, 95.0, 62.0, 28.0),
                    weatherCode = listOf(1, 2, 3, 63, 95, 82, 61),
                    windSpeed10m = listOf(10.0, 12.0, 18.0, 33.0, 61.0, 42.0, 24.0),
                ),
            )
        )

        val repository = RouteRepositoryImpl(
            routeDao = routeDao,
            routeDirectionsProvider = routeDirectionsProvider,
            openMeteoApi = openMeteoApi,
            locationResolver = locationResolver,
            io = Dispatchers.Unconfined,
        )

        val refresh = repository.refreshRoutes(
            origin = "Sector 21",
            destination = "Connaught Place",
            mode = RoutingMode.FASTEST,
        )
        assertTrue(refresh.isSuccess)

        val routes = refresh.getOrNull().orEmpty()
        assertTrue(routes.size >= 2)
        val shortestEta = routes.minByOrNull { it.etaMinutes }
        val longestEta = routes.maxByOrNull { it.etaMinutes }
        assertNotNull(shortestEta)
        assertNotNull(longestEta)

        val shortestRisk = shortestEta?.weatherRiskScore ?: 0f
        val longestRisk = longestEta?.weatherRiskScore ?: 0f
        assertTrue("Expected longer ETA route to have higher weather risk", longestRisk > shortestRisk)
    }
}
