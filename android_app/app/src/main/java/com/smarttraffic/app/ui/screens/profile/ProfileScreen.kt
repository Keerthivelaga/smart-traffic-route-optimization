package com.smarttraffic.app.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smarttraffic.designsystem.components.AchievementChip
import com.smarttraffic.designsystem.components.GlassCard
import com.smarttraffic.designsystem.components.PremiumTopBar

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onLogin: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile = state.profile

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PremiumTopBar(
            title = "Profile",
            subtitle = "Reputation and driving behavior",
            actions = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
        )

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val headline = state.email?.takeIf { it.isNotBlank() } ?: profile.displayName
                Text(headline, style = MaterialTheme.typography.headlineLarge)
                if (!state.email.isNullOrBlank()) {
                    Text(
                        "User ID: ${profile.userId}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    )
                } else {
                    Text(
                        if (state.isAuthenticated) {
                            "User ID: ${profile.userId}"
                        } else {
                            "Guest mode"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    )
                }
                if (profile.trustScore <= 0f && profile.reputation <= 0f) {
                    Text("Trust unrated", style = MaterialTheme.typography.bodyLarge)
                    Text("Reputation 0", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Submit validated reports or GPS updates to build trust and ranking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    )
                } else {
                    Text("Trust ${(profile.trustScore * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
                    Text("Reputation ${profile.reputation.toInt()}", style = MaterialTheme.typography.bodyLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    profile.achievements.forEach { badge -> AchievementChip(label = badge, achieved = true) }
                }
                state.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

            Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open Settings")
            }

            if (state.isAuthenticated) {
                Button(
                    onClick = {
                        viewModel.logout()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Logout")
                }
            } else {
                Button(
                    onClick = onLogin,   // navigate to login
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Login")
                }
            }
        }
    }
}


