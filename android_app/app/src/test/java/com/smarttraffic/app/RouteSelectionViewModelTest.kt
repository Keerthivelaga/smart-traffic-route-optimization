package com.smarttraffic.app

import com.smarttraffic.app.location.LocationSuggestionProvider
import com.smarttraffic.app.ui.screens.route.RouteSelectionViewModel
import com.smarttraffic.core_engine.domain.model.RouteOption
import com.smarttraffic.core_engine.domain.model.RouteWeatherSummary
import com.smarttraffic.core_engine.domain.model.RoutingMode
import com.smarttraffic.core_engine.domain.model.WeatherSeverity
import com.smarttraffic.core_engine.domain.usecase.GetRoutesUseCase
import io.mockk.coEvery
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteSelectionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var useCase: GetRoutesUseCase
    private lateinit var suggestions: LocationSuggestionProvider

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        useCase = mockk()
        suggestions = mockk()
        coEvery { suggestions.suggestLocations(any(), any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `mode switch refreshes routes`() = runTest {
        val routes = listOf(
            RouteOption("id1", "Fast Route", 20, 10f, 0.91f, RoutingMode.FASTEST),
            RouteOption("id2", "Fuel Route", 23, 11f, 0.85f, RoutingMode.FUEL_EFFICIENT),
        )
        coEvery { useCase.refresh(any(), any(), any()) } returns Result.success(routes)
        every { useCase.observe(any(), any(), any()) } returns flowOf(routes)

        val vm = RouteSelectionViewModel(useCase, suggestions)
        vm.selectMode(RoutingMode.FUEL_EFFICIENT)
        advanceUntilIdle()

        assertEquals(RoutingMode.FUEL_EFFICIENT, vm.state.value.mode)
        assertEquals(2, vm.state.value.routes.size)
    }

    @Test
    fun `extreme weather selected route exposes alternate route advisory`() = runTest {
        val severeWeather = RouteWeatherSummary(
            summary = "Severe thunderstorm expected near mid-route.",
            severity = WeatherSeverity.SEVERE,
            riskScore = 0.92f,
            checkpoints = emptyList(),
        )
        val lowWeather = RouteWeatherSummary(
            summary = "Mostly stable weather along this route.",
            severity = WeatherSeverity.LOW,
            riskScore = 0.14f,
            checkpoints = emptyList(),
        )
        val routes = listOf(
            RouteOption(
                id = "r_severe",
                title = "Fast Route",
                etaMinutes = 24,
                distanceKm = 10f,
                confidence = 0.82f,
                mode = RoutingMode.FASTEST,
                weather = severeWeather,
                weatherRiskScore = 0.92f,
            ),
            RouteOption(
                id = "r_safe",
                title = "Alt Route",
                etaMinutes = 29,
                distanceKm = 11.8f,
                confidence = 0.79f,
                mode = RoutingMode.FASTEST,
                weather = lowWeather,
                weatherRiskScore = 0.14f,
            ),
        )
        coEvery { useCase.refresh(any(), any(), any()) } returns Result.success(routes)
        every { useCase.observe(any(), any(), any()) } returns flowOf(routes)

        val vm = RouteSelectionViewModel(useCase, suggestions)
        advanceUntilIdle()
        vm.selectRoute("r_severe")
        advanceUntilIdle()

        assertTrue(vm.state.value.weatherAdvisory?.contains("Severe weather") == true)
        assertEquals(1, vm.state.value.alternateRoutes.size)
        assertEquals("r_safe", vm.state.value.alternateRoutes.first().id)
    }
}

