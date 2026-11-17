package com.boucle.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boucle.app.healthconnect.HealthConnectAvailability
import com.boucle.app.polar.Connection
import com.boucle.app.ui.navigation.Destination
import com.boucle.app.ui.screens.ActivityScreen
import com.boucle.app.ui.screens.BodyScreen
import com.boucle.app.ui.screens.DevicesScreen
import com.boucle.app.ui.screens.EnergyScreen
import com.boucle.app.ui.screens.HeartScreen
import com.boucle.app.ui.screens.PairingScreen
import com.boucle.app.ui.screens.ProfileScreen
import com.boucle.app.ui.screens.RecoveryScreen
import com.boucle.app.ui.screens.SleepScreen
import com.boucle.app.ui.screens.TodayScreen
import com.boucle.app.ui.screens.Vo2MaxScreen
import com.boucle.app.ui.screens.WelcomeScreen
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.polar.sdk.api.model.PolarFirstTimeUseConfig
import java.time.format.DateTimeFormatter

private val bluetoothPermissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

private fun Context.hasBluetoothPermissions(): Boolean = bluetoothPermissions.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

private val tabs = listOf(
    Triple(Destination.Today, "Aujourd'hui", BoucleIcons.Today),
    Triple(Destination.Sleep, "Sommeil", BoucleIcons.Moon),
    Triple(Destination.Activity, "Forme", BoucleIcons.Pulse),
    Triple(Destination.Devices, "Appareils", BoucleIcons.Band),
)

private val stackSaver = listSaver<List<Destination>, String>(
    save = { stack -> stack.map { it.name } },
    restore = { names -> names.map(Destination::valueOf) },
)

private val birthDateFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu")

@Composable
fun BoucleRoot(viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(context.hasBluetoothPermissions()) }
    var stack by rememberSaveable(stateSaver = stackSaver) { mutableStateOf(listOf(Destination.Today)) }

    fun navigate(destination: Destination) {
        stack = if (destination.isTab) listOf(destination) else stack + destination
    }

    fun back() {
        stack = when {
            stack.size > 1 -> stack.dropLast(1)
            stack.first() != Destination.Today -> listOf(Destination.Today)
            else -> stack
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionsGranted = result.values.all { it }
        if (permissionsGranted) viewModel.completeOnboarding()
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) viewModel.onPermissionsGranted()
    }

    val healthConnectLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted -> viewModel.onHealthConnectPermissionsResult(granted) }
    // Au retour dans l'app (installation de Health Connect, autorisations changées dans les réglages).
    LifecycleResumeEffect(Unit) {
        viewModel.refreshHealthConnect()
        onPauseOrDispose { }
    }

    val deviceState by viewModel.deviceState.collectAsStateWithLifecycle()
    val dayState by viewModel.dayState.collectAsStateWithLifecycle()

    // Bracelet connecté mais pas encore configuré : on ouvre le formulaire de profil ; une fois envoyé, on le referme.
    val needsSetup = deviceState.link.connection == Connection.CONNECTED && deviceState.setupDone == false
    LaunchedEffect(needsSetup) {
        if (needsSetup && stack.last() != Destination.Profile) {
            stack = listOf(Destination.Devices, Destination.Profile)
        } else if (!needsSetup && deviceState.setupDone == true && stack.last() == Destination.Profile) {
            stack = stack.dropLast(1)
        }
    }
    // Une fois un bracelet choisi, l'écran de recherche laisse place à l'écran Appareils.
    LaunchedEffect(deviceState.savedDevice) {
        if (deviceState.savedDevice != null && stack.last() == Destination.Pairing) stack = stack.dropLast(1)
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(deviceState.message) {
        val message = deviceState.message ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(message, actionLabel = "OK", withDismissAction = false)
        if (result == SnackbarResult.ActionPerformed || result == SnackbarResult.Dismissed) viewModel.dismissMessage()
    }

    val showWelcome = deviceState.onboardingDone == false && deviceState.savedDevice == null && !permissionsGranted
    if (deviceState.onboardingDone == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    if (showWelcome) {
        WelcomeScreen(
            onAllowBluetooth = {
                stack = listOf(Destination.Devices, Destination.Pairing)
                permissionLauncher.launch(bluetoothPermissions)
            },
            onTryDemo = { viewModel.loadDemo { stack = listOf(Destination.Today) } },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    BackHandler(enabled = stack.size > 1 || stack.first() != Destination.Today) { back() }

    val current = stack.last()
    val syncReady = deviceState.link.connection == Connection.CONNECTED && deviceState.setupDone == true
    val healthConnectReady = deviceState.healthConnect.availability == HealthConnectAvailability.AVAILABLE &&
        deviceState.healthConnect.granted > 0
    val refresh: () -> Unit = {
        when {
            syncReady -> viewModel.sync()
            healthConnectReady -> viewModel.syncHealthConnect()
            else -> navigate(Destination.Devices)
        }
    }

    Scaffold(
        containerColor = BoucleColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(
                visible = current.isTab,
                enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } + fadeIn(),
                exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeOut(),
            ) {
                BoucleNavigationBar(current = stack.first(), onSelect = ::navigate)
            }
        },
    ) { padding ->
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
        val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val scaleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        val fastEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = stack,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val from = initialState
                val to = targetState
                when {
                    // Avancer vers un écran de détail : il glisse depuis la droite.
                    to.size > from.size -> (slideInHorizontally(spatial) { it / 3 } + fadeIn(effects))
                        .togetherWith(slideOutHorizontally(spatial) { -it / 6 } + fadeOut(effects))
                    // Revenir : l'inverse.
                    to.size < from.size -> (slideInHorizontally(spatial) { -it / 6 } + fadeIn(effects))
                        .togetherWith(slideOutHorizontally(spatial) { it / 3 } + fadeOut(effects))
                    // Changer d'onglet : fondu enchaîné avec un léger zoom.
                    else -> (fadeIn(effects) + scaleIn(scaleSpec, initialScale = 0.94f))
                        .togetherWith(fadeOut(fastEffects))
                }
            },
            contentKey = { it.joinToString { d -> d.name } },
            label = "screens",
        ) { target ->
            val screenModifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
            when (target.last()) {
                Destination.Today -> TodayScreen(
                    state = dayState,
                    lastSyncAt = deviceState.lastSyncAt ?: deviceState.healthConnect.lastSyncAt,
                    refreshing = deviceState.syncing || deviceState.healthConnect.syncing,
                    onSelectDate = viewModel::selectDate,
                    onRefresh = refresh,
                    onSyncClick = refresh,
                    onNavigate = ::navigate,
                    modifier = screenModifier,
                )
                Destination.Sleep -> SleepScreen(dayState, screenModifier)
                Destination.Activity -> ActivityScreen(dayState, ::navigate, screenModifier)
                Destination.Devices -> DevicesScreen(
                    state = deviceState,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { permissionLauncher.launch(bluetoothPermissions) },
                    onReconnect = viewModel::reconnect,
                    onForgetDevice = viewModel::forgetDevice,
                    onRecheckSetup = viewModel::recheckSetup,
                    onSync = viewModel::sync,
                    onRequestHealthConnect = { healthConnectLauncher.launch(viewModel.healthConnectPermissions) },
                    onSyncHealthConnect = viewModel::syncHealthConnect,
                    onInstallHealthConnect = {
                        runCatching { context.startActivity(viewModel.healthConnectInstallIntent()) }
                    },
                    onLoadDemo = { viewModel.loadDemo { stack = listOf(Destination.Today) } },
                    onClearData = viewModel::clearData,
                    onNavigate = ::navigate,
                    modifier = screenModifier,
                )
                Destination.Recovery -> RecoveryScreen(dayState, ::back, screenModifier)
                Destination.Energy -> EnergyScreen(dayState, ::back, screenModifier)
                Destination.Heart -> HeartScreen(dayState, ::back, screenModifier)
                Destination.Vo2Max -> Vo2MaxScreen(dayState, ::back, ::navigate, screenModifier)
                Destination.Body -> BodyScreen(dayState, deviceState.healthConnect, ::back, ::navigate, screenModifier)
                Destination.Pairing -> PairingScreen(
                    state = deviceState,
                    onStartScan = viewModel::startScan,
                    onStopScan = viewModel::stopScan,
                    onSelectDevice = viewModel::selectDevice,
                    onBack = ::back,
                    modifier = screenModifier,
                )
                Destination.Profile -> ProfileScreen(
                    initial = deviceState.profile.toForm(),
                    saving = deviceState.savingProfile,
                    canSubmit = deviceState.link.connection == Connection.CONNECTED,
                    onSubmit = viewModel::submitProfile,
                    onBack = ::back,
                    modifier = screenModifier,
                )
            }
        }
    }
}

/** Préremplit le formulaire avec le profil déjà enregistré. */
private fun com.boucle.app.data.UserProfile?.toForm(): ProfileForm {
    if (this == null) return ProfileForm()
    return ProfileForm(
        female = female,
        birthDate = birthDate.format(birthDateFormat),
        heightCm = heightCm.takeIf { it > 0f }?.let { "%.0f".format(it) } ?: "",
        weightKg = weightKg.takeIf { it > 0f }?.let { "%.1f".format(java.util.Locale.FRENCH, it) } ?: "",
        maxHr = maxHr?.toString() ?: "",
        typicalDay = PolarFirstTimeUseConfig.TypicalDay.MOSTLY_SITTING,
    )
}

@Composable
private fun BoucleNavigationBar(current: Destination, onSelect: (Destination) -> Unit) {
    NavigationBar(containerColor = BoucleColors.Surface, tonalElevation = 0.dp) {
        tabs.forEach { (destination, label, icon) ->
            val selected = destination == current
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                label = { Text(label, style = if (selected) BoucleType.navLabelSelected else BoucleType.navLabel) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = BoucleColors.OnPrimaryContainer,
                    selectedTextColor = BoucleColors.OnPrimaryContainer,
                    indicatorColor = BoucleColors.PrimaryContainer,
                    unselectedIconColor = BoucleColors.OnSurfaceVariant,
                    unselectedTextColor = BoucleColors.OnSurfaceVariant,
                ),
            )
        }
    }
}
