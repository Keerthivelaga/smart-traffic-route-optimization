package com.smarttraffic.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.smarttraffic.app.ui.screens.analytics.AnalyticsScreen
import com.smarttraffic.app.ui.screens.auth.LoginScreen
import com.smarttraffic.app.ui.screens.leaderboard.LeaderboardScreen
import com.smarttraffic.app.ui.screens.map.LiveMapScreen
import com.smarttraffic.app.ui.screens.navigationmode.NavigationModeScreen
import com.smarttraffic.app.ui.screens.onboarding.OnboardingScreen
import com.smarttraffic.app.ui.screens.profile.ProfileScreen
import com.smarttraffic.app.ui.screens.reporting.ReportingScreen
import com.smarttraffic.app.ui.screens.route.RouteSelectionScreen
import com.smarttraffic.app.ui.screens.settings.SettingsScreen
import com.smarttraffic.app.ui.screens.splash.SecureBlockedScreen
import com.smarttraffic.app.ui.screens.splash.SplashScreen

@Composable
fun TrafficNavHost(
    isCompromised: Boolean,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        modifier = modifier,
        startDestination = if (isCompromised) Destination.SecureBlocked.route else Destination.Splash.route,
    ) {
        composable(
            route = Destination.Splash.route,
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, tween(420)) },
            exitTransition = { fadeOut(tween(220)) },
        ) {
            SplashScreen(
                onComplete = {
                    navController.navigate(Destination.Onboarding.route) {
                        popUpTo(Destination.Splash.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Destination.SecureBlocked.route) { SecureBlockedScreen() }
        composable(Destination.Onboarding.route) {
            OnboardingScreen(onContinue = { navController.navigate(Destination.Login.createRoute()) })
        }
        composable(
            route = Destination.Login.route,
            arguments = listOf(
                navArgument(Destination.Login.ARG_REDIRECT) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
        ) { backStackEntry ->
            val redirectRoute = backStackEntry.arguments?.getString(Destination.Login.ARG_REDIRECT)
            LoginScreen(
                onAuthSuccess = {
                    val target = redirectRoute?.trim().takeUnless { it.isNullOrBlank() } ?: Destination.LiveMap.route
                    navController.navigate(target) {
                        popUpTo(Destination.Login.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onContinueAsGuest = {
                    navController.navigate(Destination.LiveMap.route) {
                        popUpTo(Destination.Login.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Destination.LiveMap.route) {
            LiveMapScreen(
                onRouteOptions = { navController.navigate(Destination.RouteSelection.route) },
                onAnalytics = { navController.navigate(Destination.Analytics.route) },
                onReport = { navController.navigate(Destination.Reporting.route) },
                onLeaderboard = { navController.navigate(Destination.Leaderboard.route) },
                onProfile = { navController.navigate(Destination.Profile.route) },
            )
        }
        composable(Destination.RouteSelection.route) {
            RouteSelectionScreen(
                onStartNavigation = { origin, destination, mode, routeId ->
                    navController.navigate(
                        Destination.NavigationMode.createRoute(
                            origin = origin,
                            destination = destination,
                            mode = mode,
                            routeId = routeId,
                        )
                    )
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Destination.NavigationMode.route,
            arguments = listOf(
                navArgument(Destination.NavigationMode.ARG_ORIGIN) { type = NavType.StringType },
                navArgument(Destination.NavigationMode.ARG_DESTINATION) { type = NavType.StringType },
                navArgument(Destination.NavigationMode.ARG_MODE) { type = NavType.StringType },
                navArgument(Destination.NavigationMode.ARG_ROUTE_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            NavigationModeScreen(
                onRouteOptions = { navController.navigate(Destination.RouteSelection.route) },
                onAnalytics = { navController.navigate(Destination.Analytics.route) },
                onReport = { navController.navigate(Destination.Reporting.route) },
                onLeaderboard = { navController.navigate(Destination.Leaderboard.route) },
                onExit = { navController.popBackStack(Destination.LiveMap.route, false) },
            )
        }
        composable(Destination.Analytics.route) {
            AnalyticsScreen(onBack = { navController.popBackStack() })
        }
        composable(Destination.Leaderboard.route) {
            LeaderboardScreen(onBack = { navController.popBackStack() })
        }
        composable(Destination.Reporting.route) {
            ReportingScreen(
                onBack = { navController.popBackStack() },
                onLoginRequired = {
                    navController.navigate(Destination.Login.createRoute(Destination.Reporting.route)) {
                        popUpTo(Destination.Reporting.route) { inclusive = true }
                    }
                },
            )
        }
        composable(Destination.Profile.route) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(Destination.Settings.route) },
                onLogin = { navController.navigate(Destination.Login.route) }
            )
        }
        composable(Destination.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

