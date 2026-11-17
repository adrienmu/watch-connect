package com.boucle.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.boucle.app.ui.DayUiState
import com.boucle.app.ui.DeviceUiState
import com.boucle.app.ui.FoundDevice
import com.boucle.app.ui.HealthConnectUiState
import com.boucle.app.ui.ProfileForm
import com.boucle.app.ui.navigation.Destination
import java.time.LocalDate

// À REMPLACER : écran à implémenter fidèlement à la maquette.
@Composable
fun ProfileScreen(
    initial: ProfileForm,
    saving: Boolean,
    canSubmit: Boolean,
    onSubmit: (ProfileForm) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) { Text("Configuration du bracelet") }
}
