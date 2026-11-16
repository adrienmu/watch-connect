package com.boucle.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private val bluetoothPermissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

private fun Context.hasBluetoothPermissions(): Boolean = bluetoothPermissions.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun BoucleTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoucleRoot(viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(context.hasBluetoothPermissions()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> permissionsGranted = result.values.all { it } }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            viewModel.onPermissionsGranted()
        } else {
            permissionLauncher.launch(bluetoothPermissions)
        }
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
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(topBar = { TopAppBar(title = { Text("Boucle") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Bracelet") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Données") })
            }
            if (selectedTab == 0) {
                DeviceScreen(
                    state = deviceState,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { permissionLauncher.launch(bluetoothPermissions) },
                    onStartScan = viewModel::startScan,
                    onStopScan = viewModel::stopScan,
                    onSelectDevice = viewModel::selectDevice,
                    onReconnect = viewModel::reconnect,
                    onForgetDevice = viewModel::forgetDevice,
                    onRecheckSetup = viewModel::recheckSetup,
                    onSubmitProfile = viewModel::submitProfile,
                    onSync = viewModel::sync,
                    onRequestHealthConnect = { healthConnectLauncher.launch(viewModel.healthConnectPermissions) },
                    onSyncHealthConnect = viewModel::syncHealthConnect,
                    onInstallHealthConnect = {
                        runCatching { context.startActivity(viewModel.healthConnectInstallIntent()) }
                    },
                    onDismissMessage = viewModel::dismissMessage,
                    onLoadDemo = { viewModel.loadDemo { selectedTab = 1 } },
                    onClearData = viewModel::clearData,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                DataScreen(
                    state = dayState,
                    onPreviousDay = viewModel::previousDay,
                    onNextDay = viewModel::nextDay,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
