package com.smarttraffic.app.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smarttraffic.designsystem.components.GlassCard

@Composable
fun LoginScreen(
    onAuthSuccess: () -> Unit,
    onContinueAsGuest: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)
                )
            )
            .padding(20.dp)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Access to Smart Navigation",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    "Login is optional. using Guest mode you can browse maps/routes. Login is required for cloud GPS sync, real-time reporting, and personalized leaderboard updates.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = state.mode == AuthMode.Login,
                        onClick = { viewModel.updateMode(AuthMode.Login) },
                        label = { Text("Login") },
                    )
                    FilterChip(
                        selected = state.mode == AuthMode.SignUp,
                        onClick = { viewModel.updateMode(AuthMode.SignUp) },
                        label = { Text("Sign up") },
                    )
                }
                OutlinedTextField(
                    value = state.email,
                    onValueChange = viewModel::updateEmail,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !state.loading,
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::updatePassword,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !state.loading,
                )
                if (state.mode == AuthMode.SignUp) {
                    OutlinedTextField(
                        value = state.confirmPassword,
                        onValueChange = viewModel::updateConfirmPassword,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Confirm password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !state.loading,
                    )
                }
                Button(
                    onClick = { viewModel.submitAuth(onAuthSuccess) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.loading,
                ) {
                    Text(
                        if (state.loading) {
                            if (state.mode == AuthMode.SignUp) "Creating account..." else "Logging in..."
                        } else {
                            if (state.mode == AuthMode.SignUp) "Create Account" else "Login"
                        }
                    )
                }
                TextButton(
                    onClick = { viewModel.continueAsGuest(onContinueAsGuest) },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue as Guest")
                }
                state.info?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}


