package com.smarttraffic.app.navigation

import android.net.Uri
import com.smarttraffic.core_engine.domain.model.RoutingMode

private const val NAVIGATION_MODE_BASE_ROUTE = "navigation_mode"
private const val NAVIGATION_MODE_ARG_ORIGIN = "origin"
private const val NAVIGATION_MODE_ARG_DESTINATION = "destination"
private const val NAVIGATION_MODE_ARG_MODE = "mode"
private const val NAVIGATION_MODE_ARG_ROUTE_ID = "routeId"
private const val LOGIN_BASE_ROUTE = "login"
private const val LOGIN_ARG_REDIRECT = "redirect"

sealed class Destination(val route: String) {
    data object Splash : Destination("splash")
    data object Onboarding : Destination("onboarding")
    data object Login : Destination("$LOGIN_BASE_ROUTE?$LOGIN_ARG_REDIRECT={$LOGIN_ARG_REDIRECT}") {
        const val BASE_ROUTE = LOGIN_BASE_ROUTE
        const val ARG_REDIRECT = LOGIN_ARG_REDIRECT

        fun createRoute(redirectRoute: String? = null): String {
            return if (redirectRoute.isNullOrBlank()) {
                BASE_ROUTE
            } else {
                "$BASE_ROUTE?$ARG_REDIRECT=${Uri.encode(redirectRoute)}"
            }
        }
    }
    data object LiveMap : Destination("live_map")
    data object RouteSelection : Destination("route_selection")
    data object NavigationMode : Destination(
        "$NAVIGATION_MODE_BASE_ROUTE?$NAVIGATION_MODE_ARG_ORIGIN={$NAVIGATION_MODE_ARG_ORIGIN}&$NAVIGATION_MODE_ARG_DESTINATION={$NAVIGATION_MODE_ARG_DESTINATION}&$NAVIGATION_MODE_ARG_MODE={$NAVIGATION_MODE_ARG_MODE}&$NAVIGATION_MODE_ARG_ROUTE_ID={$NAVIGATION_MODE_ARG_ROUTE_ID}"
    ) {
        const val BASE_ROUTE = NAVIGATION_MODE_BASE_ROUTE
        const val ARG_ORIGIN = NAVIGATION_MODE_ARG_ORIGIN
        const val ARG_DESTINATION = NAVIGATION_MODE_ARG_DESTINATION
        const val ARG_MODE = NAVIGATION_MODE_ARG_MODE
        const val ARG_ROUTE_ID = NAVIGATION_MODE_ARG_ROUTE_ID

        fun createRoute(
            origin: String,
            destination: String,
            mode: RoutingMode,
            routeId: String,
        ): String {
            return "$BASE_ROUTE?$ARG_ORIGIN=${Uri.encode(origin)}&$ARG_DESTINATION=${Uri.encode(destination)}&$ARG_MODE=${mode.name}&$ARG_ROUTE_ID=${Uri.encode(routeId)}"
        }
    }
    data object Analytics : Destination("analytics")
    data object Leaderboard : Destination("leaderboard")
    data object Reporting : Destination("reporting")
    data object Profile : Destination("profile")
    data object Settings : Destination("settings")
    data object SecureBlocked : Destination("secure_blocked")
}

