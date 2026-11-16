package com.boucle.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.boucle.app.healthconnect.HealthConnectAvailability
import com.boucle.app.polar.Connection
import com.polar.sdk.api.model.PolarFirstTimeUseConfig.TypicalDay

@Composable
fun DeviceScreen(
    state: DeviceUiState,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectDevice: (FoundDevice) -> Unit,
    onReconnect: () -> Unit,
    onForgetDevice: () -> Unit,
    onRecheckSetup: () -> Unit,
    onSubmitProfile: (ProfileForm) -> Unit,
    onSync: () -> Unit,
    onRequestHealthConnect: () -> Unit,
    onSyncHealthConnect: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    onDismissMessage: () -> Unit,
    onLoadDemo: () -> Unit,
    onClearData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.message?.let { MessageCard(it, onDismissMessage) }

        when {
            !permissionsGranted -> PermissionCard(onRequestPermissions)
            !state.link.bluetoothOn -> InfoCard("Bluetooth désactivé", "Active le Bluetooth pour joindre le bracelet.")
            state.savedDevice == null -> PairingSection(state, onStartScan, onStopScan, onSelectDevice)
            else -> {
                StatusCard(state, onReconnect, onForgetDevice)
                if (state.link.connection == Connection.CONNECTED) {
                    when (state.setupDone) {
                        null -> SectionCard("Configuration") {
                            Text("Lecture de la configuration du bracelet…")
                            TextButton(onClick = onRecheckSetup) { Text("Revérifier") }
                        }
                        false -> ProfileSection(state.savingProfile, onSubmitProfile)
                        true -> SyncSection(state, onSync)
                    }
                }
            }
        }

        HealthConnectSection(state.healthConnect, onRequestHealthConnect, onSyncHealthConnect, onInstallHealthConnect)

        SectionCard("Mode démo") {
            Text(
                "Remplace les données locales par 14 jours de données fictives, pour essayer l'app sans bracelet.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLoadDemo) { Text("Charger la démo") }
                TextButton(onClick = onClearData) { Text("Tout effacer") }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    SectionCard(title) { Text(body) }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    SectionCard("Autorisation Bluetooth") {
        Text("L'app a besoin du Bluetooth pour parler au bracelet. Aucune donnée ne quitte le téléphone.")
        Button(onClick = onRequest) { Text("Autoriser") }
    }
}

@Composable
private fun PairingSection(
    state: DeviceUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectDevice: (FoundDevice) -> Unit,
) {
    SectionCard("Avant de commencer") {
        Text(
            "Le Polar Loop n'accepte qu'un seul téléphone ou une seule app. S'il a déjà été utilisé avec " +
                "Polar Flow, il reste invisible : il faut d'abord le réinitialiser (bouton sous le bracelet, " +
                "pendant qu'il est sur son chargeur), puis garder le téléphone à moins d'un mètre.",
        )
    }
    SectionCard("Bracelets à proximité") {
        if (state.scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onStopScan) { Text("Arrêter") }
        } else {
            Button(onClick = onStartScan) { Text("Rechercher") }
        }
        if (state.found.isEmpty() && !state.scanning) {
            Text("Aucun appareil Polar trouvé pour l'instant.", style = MaterialTheme.typography.bodySmall)
        }
        state.found.forEach { device ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.name)
                    Text("${device.id} · ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { onSelectDevice(device) }) { Text("Associer") }
            }
        }
    }
}

@Composable
private fun StatusCard(state: DeviceUiState, onReconnect: () -> Unit, onForget: () -> Unit) {
    val link = state.link
    SectionCard(state.savedDevice?.name ?: "Bracelet") {
        val status = when (link.connection) {
            Connection.CONNECTED -> "Connecté"
            Connection.CONNECTING -> "Connexion en cours…"
            Connection.DISCONNECTED -> "Déconnecté"
        }
        Text(status)
        link.batteryPercent?.let { level ->
            val suffix = if (link.charging == true) " (en charge)" else ""
            Text("Batterie : $level %$suffix")
        }
        link.firmware?.let { Text("Firmware : $it") }
        link.liveHr?.let { Text("FC en direct : $it bpm") }
        if (link.connection == Connection.DISCONNECTED) {
            link.lastDisconnectReason?.let {
                Text("Dernière déconnexion : $it", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (link.connection == Connection.DISCONNECTED) {
                Button(onClick = onReconnect) { Text("Reconnecter") }
            }
            TextButton(onClick = onForget) { Text("Oublier ce bracelet") }
        }
    }
}

@Composable
private fun HealthConnectSection(
    state: HealthConnectUiState,
    onRequest: () -> Unit,
    onSync: () -> Unit,
    onInstall: () -> Unit,
) {
    SectionCard("Health Connect") {
        Text(
            "Lit les pesées de ta balance Withings (poids, masse grasse, maigre, osseuse, hydrique), la SpO2 et la " +
                "VFC de ta montre. " +
                (
                    if (state.replacesBand) {
                        "Sans bracelet Polar, la FC, les pas, le sommeil et les séances de la montre le remplacent " +
                            "pour tous les scores. "
                    } else {
                        "Le bracelet Polar reste la source de la FC, des pas et du sommeil. "
                    }
                    ) +
                "Dans l'app Withings : Partager › Health Connect, puis coche ces données.",
            style = MaterialTheme.typography.bodySmall,
        )
        when (state.availability) {
            null -> Text("Vérification…", style = MaterialTheme.typography.bodySmall)
            HealthConnectAvailability.NOT_INSTALLED -> {
                Text("Health Connect n'est pas installé sur ce téléphone.")
                Button(onClick = onInstall) { Text("Installer Health Connect") }
            }
            HealthConnectAvailability.UPDATE_REQUIRED -> {
                Text("Health Connect doit être mis à jour.")
                Button(onClick = onInstall) { Text("Mettre à jour") }
            }
            HealthConnectAvailability.AVAILABLE -> if (state.granted == 0) {
                Button(onClick = onRequest) { Text("Autoriser la lecture") }
            } else {
                Text(
                    "${state.granted} type(s) de données autorisé(s) sur ${state.requested}. " +
                        (state.lastSyncAt?.let { "Dernière lecture : $it" } ?: "Jamais lu.") +
                        (state.lastCount?.let { " ($it mesure(s))" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSync, enabled = !state.syncing) {
                        Text(if (state.syncing) "Lecture…" else "Lire maintenant")
                    }
                    TextButton(onClick = onRequest) { Text("Autorisations") }
                }
                if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SyncSection(state: DeviceUiState, onSync: () -> Unit) {
    SectionCard("Synchronisation") {
        Text(
            state.lastSyncAt?.let { "Dernière synchronisation : $it" } ?: "Jamais synchronisé.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onSync, enabled = !state.syncing) {
            Text(if (state.syncing) "Synchronisation…" else "Synchroniser")
        }
        if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.log.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

private val trainingBackgrounds = listOf(
    10 to "Occasionnel",
    20 to "Régulier",
    30 to "Fréquent",
    40 to "Intensif",
    50 to "Semi-pro",
    60 to "Pro",
)

private val typicalDays = listOf(
    TypicalDay.MOSTLY_SITTING to "Plutôt assis",
    TypicalDay.MOSTLY_STANDING to "Plutôt debout",
    TypicalDay.MOSTLY_MOVING to "Plutôt en mouvement",
)

@Composable
private fun ProfileSection(saving: Boolean, onSubmit: (ProfileForm) -> Unit) {
    var female by rememberSaveable { mutableStateOf(false) }
    var birthDate by rememberSaveable { mutableStateOf("") }
    var height by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("") }
    var maxHr by rememberSaveable { mutableStateOf("") }
    var restingHr by rememberSaveable { mutableStateOf("60") }
    var vo2Max by rememberSaveable { mutableStateOf("40") }
    var background by rememberSaveable { mutableStateOf(20) }
    var typicalDay by rememberSaveable { mutableStateOf(TypicalDay.MOSTLY_SITTING) }
    var sleepGoal by rememberSaveable { mutableStateOf("8") }

    SectionCard("Configuration du bracelet") {
        Text(
            "Le bracelet a besoin de ton profil pour calculer calories, activité et sommeil. " +
                "Ces valeurs restent sur le bracelet et le téléphone.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !female, onClick = { female = false }, label = { Text("Homme") })
            FilterChip(selected = female, onClick = { female = true }, label = { Text("Femme") })
        }
        ProfileField("Date de naissance (JJ/MM/AAAA)", birthDate, { birthDate = it }, KeyboardType.Text)
        ProfileField("Taille (cm)", height, { height = it })
        ProfileField("Poids (kg)", weight, { weight = it })
        ProfileField("FC max (bpm, à défaut 220 − âge)", maxHr, { maxHr = it })
        ProfileField("FC de repos (bpm)", restingHr, { restingHr = it })
        ProfileField("VO2max estimée (ml/kg/min)", vo2Max, { vo2Max = it })
        ProfileField("Objectif de sommeil (heures, 5 à 11)", sleepGoal, { sleepGoal = it })

        Text("Niveau d'entraînement", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            trainingBackgrounds.forEach { (value, label) ->
                FilterChip(selected = background == value, onClick = { background = value }, label = { Text(label) })
            }
        }
        Text("Journée type", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            typicalDays.forEach { (value, label) ->
                FilterChip(selected = typicalDay == value, onClick = { typicalDay = value }, label = { Text(label) })
            }
        }

        Button(
            enabled = !saving,
            onClick = {
                onSubmit(
                    ProfileForm(
                        female = female,
                        birthDate = birthDate,
                        heightCm = height,
                        weightKg = weight,
                        maxHr = maxHr,
                        restingHr = restingHr,
                        vo2Max = vo2Max,
                        trainingBackground = background,
                        typicalDay = typicalDay,
                        sleepGoalHours = sleepGoal,
                    ),
                )
            },
        ) { Text(if (saving) "Envoi au bracelet…" else "Configurer le bracelet") }
    }
}

@Composable
private fun ProfileField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Decimal,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}
