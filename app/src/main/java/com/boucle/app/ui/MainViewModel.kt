package com.boucle.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.boucle.app.BoucleApp
import com.boucle.app.data.PendingBackupEnable
import com.boucle.app.data.SavedDevice
import com.boucle.app.data.UserProfile
import com.boucle.app.data.backup.BackupCrypto
import com.boucle.app.data.backup.BackupFolder
import com.boucle.app.data.backup.FolderPickAction
import com.boucle.app.data.backup.WrongPassphraseException
import com.boucle.app.data.backup.decideFolderPick
import com.boucle.app.data.backup.discardKeyOnPickCancelled
import com.boucle.app.data.backup.wipe
import com.boucle.app.data.export.BackupFormatException
import com.boucle.app.data.export.DataExporter
import com.boucle.app.data.export.DataImporter
import android.net.Uri
import androidx.core.net.toUri
import com.boucle.app.domain.BodyBatteryDay
import com.boucle.app.domain.DailyGoals
import com.boucle.app.domain.StepHistory
import com.boucle.app.domain.BodyBatteryInputs
import com.boucle.app.domain.BodyBatteryModel
import com.boucle.app.domain.BodyMeasurements
import com.boucle.app.domain.MeasurementType
import com.boucle.app.domain.HrvStress
import com.boucle.app.domain.RecoveryScore
import com.boucle.app.domain.RecoveryScoring
import com.boucle.app.domain.SleepScore
import com.boucle.app.domain.Vo2MaxEstimation
import com.boucle.app.domain.Wellbeing
import com.boucle.app.domain.WellbeingDay
import com.boucle.app.domain.WellbeingNight
import com.boucle.app.data.db.ExternalMeasurementEntity
import com.boucle.app.data.db.PpiBlockEntity
import com.boucle.app.data.db.SleepEntity
import kotlinx.coroutines.flow.onStart
import com.boucle.app.domain.scoreNight
import com.boucle.app.domain.HrZoneTime
import com.boucle.app.domain.RouteAnalysis
import com.boucle.app.domain.RouteCodec
import com.boucle.app.domain.RouteSource
import com.boucle.app.domain.RouteStats
import com.boucle.app.domain.RouteStatus
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.data.db.ActivityEditEntity
import com.boucle.app.data.db.TrainingRouteEntity
import com.boucle.app.domain.ActivityDetection
import com.boucle.app.domain.CalorieEstimate
import com.boucle.app.domain.DetectedActivity
import com.boucle.app.domain.Exploration
import com.boucle.app.domain.ExploredGrid
import com.boucle.app.domain.HrZones
import com.boucle.app.domain.RoutePoint
import com.boucle.app.domain.Sport
import com.boucle.app.domain.Terrain
import com.boucle.app.tracking.GpsPreview
import com.boucle.app.tracking.RecordedActivity
import com.boucle.app.tracking.RecordingState
import java.time.LocalTime
import androidx.health.connect.client.records.ExerciseRoute
import kotlinx.coroutines.flow.flowOf
import com.boucle.app.healthconnect.HealthConnectAvailability
import com.boucle.app.withings.WithingsImporter
import com.boucle.app.polar.Connection
import com.boucle.app.polar.LinkState
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.model.PolarFirstTimeUseConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private data class BodyReadings(
    val summary: com.boucle.app.domain.BodySummary?,
    val spo2: com.boucle.app.domain.Spo2Stats?,
    val weights: List<WeightPoint>,
)

private data class DayScores(
    val sleep: SleepScore?,
    val recovery: RecoveryScore?,
    val battery: BodyBatteryDay?,
)

/** Demande d'accès à un tracé Health Connect en cours pour la séance affichée. */
enum class RouteAccess { IDLE, LOADING, DENIED }

/** Détail d'une séance : chiffres, tracé GPS analysé et zones cardiaques. */
data class SessionDetailUiState(
    val path: String? = null,
    val session: TrainingSessionEntity? = null,
    /** `null` : séance sans tracé (en intérieur). */
    val routeStatus: RouteStatus? = null,
    val routeSource: RouteSource? = null,
    val stats: RouteStats? = null,
    /** FC max qui sert aux zones, et si elle est estimée d'après l'âge. */
    val maxHr: Int? = null,
    val maxHrEstimated: Boolean = false,
    val zones: List<HrZoneTime> = emptyList(),
    /** Sport et terrain de la séance, corrections de l'utilisateur comprises. */
    val sport: Sport? = null,
    val terrain: Terrain? = null,
    val notes: String? = null,
    val access: RouteAccess = RouteAccess.IDLE,
    /** Vrai une fois la séance lue dans la base. */
    val loaded: Boolean = false,
)

/** Carte d'exploration : carrés traversés par les tracés GPS ; [loaded] passe à vrai une fois les tracés lus. */
data class ExplorationUiState(
    val grid: ExploredGrid = ExploredGrid.Empty,
    /** Nombre de séances avec un tracé. */
    val routes: Int = 0,
    val loaded: Boolean = false,
)

data class FoundDevice(val id: String, val name: String, val rssi: Int)

data class HealthConnectUiState(
    /** `null` tant que la disponibilité n'a pas été vérifiée. */
    val availability: HealthConnectAvailability? = null,
    val granted: Int = 0,
    val requested: Int = 0,
    val syncing: Boolean = false,
    val lastSyncAt: String? = null,
    /** Nombre de mesures lues à la dernière lecture de cette session. */
    val lastCount: Int? = null,
    /** Vrai sans bracelet Polar : la montre fournit alors FC, pas, sommeil et séances. */
    val replacesBand: Boolean = false,
)

/** Saisie du profil physique, en texte tant qu'elle n'est pas validée. */
data class ProfileForm(
    val female: Boolean = false,
    val birthDate: String = "",
    val heightCm: String = "",
    val weightKg: String = "",
    val maxHr: String = "",
    val restingHr: String = "60",
    val vo2Max: String = "40",
    val trainingBackground: Int = 20,
    val typicalDay: PolarFirstTimeUseConfig.TypicalDay = PolarFirstTimeUseConfig.TypicalDay.MOSTLY_SITTING,
    val sleepGoalHours: String = "8",
)

/** Sauvegarde automatique dans un dossier choisi par l'utilisateur. */
data class BackupUiState(
    val enabled: Boolean = false,
    val folderName: String? = null,
    /** Faux quand la permission sur le dossier a été perdue (autre téléphone, accès retiré). */
    val folderAccessible: Boolean = true,
    val running: Boolean = false,
    /** Dernière sauvegarde réussie (dd/MM/yyyy HH:mm) et son fichier. */
    val lastSuccessAt: String? = null,
    val lastFile: String? = null,
    /** Échec de la dernière tentative, effacé à la sauvegarde réussie suivante. */
    val lastErrorAt: String? = null,
    val lastError: String? = null,
    /** Sauvegardes chiffrées par une phrase de passe. */
    val encrypted: Boolean = false,
    /** Faux quand la clé de chiffrement ne se relit pas sur ce téléphone (transfert) : phrase de passe à ressaisir. */
    val keyAvailable: Boolean = true,
    /** Dérivation et enregistrement d'une nouvelle clé en cours. */
    val savingKey: Boolean = false,
    /** Données de démo chargées : aucune sauvegarde tant qu'elles n'ont pas été effacées. */
    val demoMode: Boolean = false,
)

data class DeviceUiState(
    val link: LinkState = LinkState(),
    val savedDevice: SavedDevice? = null,
    val lastSyncAt: String? = null,
    val scanning: Boolean = false,
    val found: List<FoundDevice> = emptyList(),
    /** `null` tant que le bracelet n'a pas répondu. */
    val setupDone: Boolean? = null,
    val savingProfile: Boolean = false,
    val syncing: Boolean = false,
    val log: List<String> = emptyList(),
    val message: String? = null,
    val healthConnect: HealthConnectUiState = HealthConnectUiState(),
    /** Export des données en cours d'écriture. */
    val exporting: Boolean = false,
    /** Sauvegarde choisie, en attente de confirmation avant l'import. */
    val importPending: Boolean = false,
    /** La sauvegarde choisie est chiffrée : la confirmation demande la phrase de passe. */
    val importEncrypted: Boolean = false,
    /** Vérification de la phrase de passe saisie pour l'import. */
    val importChecking: Boolean = false,
    /** « Phrase de passe incorrecte. », affiché dans le dialogue d'import. */
    val importPassphraseError: String? = null,
    /** Import d'une sauvegarde en cours. */
    val importing: Boolean = false,
    val backup: BackupUiState = BackupUiState(),
    /** L'activation est enregistrée : l'écran doit ouvrir le sélecteur de dossier, puis appeler `onBackupFolderPickLaunched`. */
    val backupFolderPickRequested: Boolean = false,
    /** Profil enregistré (pour préremplir le formulaire de configuration). */
    val profile: UserProfile? = null,
    /** `null` tant que les réglages n'ont pas été lus. */
    val onboardingDone: Boolean? = null,
)

class MainViewModel(app: BoucleApp) : ViewModel() {

    private val gateway = app.gateway
    private val settings = app.settings
    private val syncEngine = app.syncEngine
    private val database = app.database
    private val dao = database.healthDao()
    private val healthConnect = app.healthConnect
    private val contentResolver = app.contentResolver
    private val exporter = DataExporter(dao)
    private val importer = DataImporter(database, settings)
    private val withingsImporter = WithingsImporter(database, app.cacheDir)
    private val autoBackup = app.autoBackup
    private val backupKeys = app.backupKeys
    private val recorder = app.recorder

    private data class Local(
        val scanning: Boolean = false,
        val found: List<FoundDevice> = emptyList(),
        val setupDone: Boolean? = null,
        val savingProfile: Boolean = false,
        val syncing: Boolean = false,
        val log: List<String> = emptyList(),
        val message: String? = null,
        val healthConnect: HealthConnectUiState = HealthConnectUiState(),
        val exporting: Boolean = false,
        val pendingImport: Uri? = null,
        val pendingImportEncrypted: Boolean = false,
        val importChecking: Boolean = false,
        val importPassphraseError: String? = null,
        val importing: Boolean = false,
        val backupFolderAccessible: Boolean = true,
        val backupKeyAvailable: Boolean = true,
        val savingBackupKey: Boolean = false,
        val backupFolderPickRequested: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    /** Le bracelet traite une commande à la fois : tout appel au SDK passe par ce verrou. */
    private val deviceMutex = Mutex()
    private var scanJob: Job? = null
    private var autoConnectDone = false

    val deviceState: StateFlow<DeviceUiState> =
        combine(gateway.state, settings.flow, local, autoBackup.running) { link, saved, local, backupRunning ->
            DeviceUiState(
                link = link,
                savedDevice = saved.device,
                lastSyncAt = saved.lastSyncAt,
                scanning = local.scanning,
                found = local.found,
                setupDone = local.setupDone,
                savingProfile = local.savingProfile,
                syncing = local.syncing,
                log = local.log,
                message = local.message,
                exporting = local.exporting,
                importPending = local.pendingImport != null,
                importEncrypted = local.pendingImportEncrypted,
                importChecking = local.importChecking,
                importPassphraseError = local.importPassphraseError,
                importing = local.importing,
                backup = BackupUiState(
                    enabled = saved.backup.enabled,
                    folderName = saved.backup.folderName,
                    folderAccessible = local.backupFolderAccessible,
                    running = backupRunning,
                    lastSuccessAt = saved.backup.lastSuccessAt,
                    lastFile = saved.backup.lastFile,
                    lastErrorAt = saved.backup.lastErrorAt,
                    lastError = saved.backup.lastError,
                    encrypted = saved.backup.encryption != null,
                    keyAvailable = saved.backup.encryption == null || local.backupKeyAvailable,
                    savingKey = local.savingBackupKey,
                    demoMode = saved.demoMode,
                ),
                backupFolderPickRequested = local.backupFolderPickRequested,
                healthConnect = local.healthConnect.copy(
                    lastSyncAt = saved.lastHealthConnectSyncAt,
                    replacesBand = saved.device == null,
                ),
                profile = saved.profile,
                onboardingDone = saved.onboardingDone,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceUiState())

    private val selectedDate = MutableStateFlow(LocalDate.now())

    @OptIn(ExperimentalCoroutinesApi::class)
    val dayState: StateFlow<DayUiState> = selectedDate
        .flatMapLatest { dayFlow(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState(LocalDate.now()))

    /** Pas de tous les jours mesurés et objectifs en vigueur : semaines, mois et séries de l'historique. */
    val stepHistory: StateFlow<StepHistory> = combine(
        dao.dailySteps(),
        settings.flow.map { it.goals }.distinctUntilChanged(),
    ) { days, goals ->
        val steps = days.mapNotNull { day ->
            runCatching { LocalDate.parse(day.date) }.getOrNull()?.let { it to day.steps }
        }.toMap()
        StepHistory(steps, goals)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StepHistory())

    private val selectedSession = MutableStateFlow<String?>(null)
    private val routeAccess = MutableStateFlow(RouteAccess.IDLE)

    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionDetail: StateFlow<SessionDetailUiState> = selectedSession
        .flatMapLatest { path -> if (path == null) flowOf(SessionDetailUiState()) else sessionDetailFlow(path) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionDetailUiState())

    init {
        refreshHealthConnect()
        refreshBackupFolder()
        // Rattrape la sauvegarde quotidienne si WorkManager l'a perdue (KEEP : sans effet si elle est déjà planifiée).
        viewModelScope.launch { if (settings.flow.first().backup.enabled) autoBackup.schedulePeriodic() }
        viewModelScope.launch {
            gateway.state
                .map {
                    it.connection == Connection.CONNECTED &&
                        PolarBleSdkFeature.FEATURE_POLAR_FILE_TRANSFER in it.readyFeatures
                }
                .distinctUntilChanged()
                .collectLatest { ready ->
                    if (ready) checkSetup() else local.update { it.copy(setupDone = null) }
                }
        }
    }

    fun onPermissionsGranted() {
        if (autoConnectDone) return
        autoConnectDone = true
        viewModelScope.launch {
            val device = settings.flow.first().device ?: return@launch
            if (gateway.state.value.connection == Connection.DISCONNECTED) connect(device.id)
        }
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            local.update { it.copy(scanning = true, found = emptyList(), message = null) }
            try {
                withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    gateway.scan().collect { info ->
                        val device = FoundDevice(info.deviceId, info.name, info.rssi)
                        local.update { state ->
                            state.copy(found = state.found.filterNot { it.id == device.id } + device)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Recherche impossible", e)
            } finally {
                local.update { it.copy(scanning = false) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
    }

    fun selectDevice(device: FoundDevice) {
        stopScan()
        viewModelScope.launch {
            settings.saveDevice(SavedDevice(device.id, device.name))
            connect(device.id)
        }
    }

    fun reconnect() {
        viewModelScope.launch {
            settings.flow.first().device?.let { connect(it.id) }
        }
    }

    fun forgetDevice() {
        viewModelScope.launch {
            settings.flow.first().device?.let { device ->
                try {
                    gateway.disconnect(device.id)
                } catch (e: Exception) {
                    report("Déconnexion impossible", e)
                }
            }
            settings.forgetDevice()
            local.update {
                Local(
                    healthConnect = it.healthConnect,
                    backupFolderAccessible = it.backupFolderAccessible,
                    backupKeyAvailable = it.backupKeyAvailable,
                )
            }
        }
    }

    fun recheckSetup() {
        viewModelScope.launch { checkSetup() }
    }

    fun submitProfile(form: ProfileForm) {
        val deviceId = gateway.state.value.deviceId ?: return
        val config = try {
            form.toConfig()
        } catch (e: IllegalArgumentException) {
            local.update { it.copy(message = e.message) }
            return
        }
        viewModelScope.launch {
            local.update { it.copy(savingProfile = true, message = null) }
            try {
                deviceMutex.withLock { gateway.api.doFirstTimeUse(deviceId, config) }
                settings.saveProfile(
                    UserProfile(
                        female = config.gender == PolarFirstTimeUseConfig.Gender.FEMALE,
                        birthDate = config.birthDate,
                        heightCm = config.height,
                        weightKg = config.weight,
                        maxHr = config.maxHeartRate,
                    ),
                )
                local.update { it.copy(setupDone = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Configuration du bracelet impossible", e)
            } finally {
                local.update { it.copy(savingProfile = false) }
            }
        }
    }

    fun sync() {
        val deviceId = gateway.state.value.deviceId ?: return
        if (local.value.syncing) return
        local.update { it.copy(syncing = true, log = emptyList(), message = null) }
        viewModelScope.launch {
            try {
                val today = LocalDate.now()
                val lastSynced = settings.flow.first().lastSyncedDate?.let(LocalDate::parse)
                // On reprend un jour avant le dernier jour synchronisé : ses données ont pu évoluer.
                val from = (lastSynced ?: today.minusDays(FIRST_SYNC_DAYS)).minusDays(1)
                val ok = deviceMutex.withLock {
                    syncEngine.sync(deviceId, from, today) { line ->
                        local.update { it.copy(log = it.log + line) }
                    }
                }
                if (ok) {
                    // saveSync signale aussi le changement des données : la sauvegarde automatique suit.
                    settings.saveSync(today.toString(), LocalDateTime.now().format(SYNC_TIME_FORMAT))
                    requestBackupAfterChange()
                }
            } finally {
                local.update { it.copy(syncing = false) }
            }
            // Les pesées et la SpO2 Withings suivent la synchronisation du bracelet.
            syncHealthConnect()
        }
    }

    /** Autorisations Health Connect à demander (lecture seule). */
    val healthConnectPermissions: Set<String> get() = healthConnect.permissions

    fun healthConnectInstallIntent() = healthConnect.installIntent()

    /** Vérifie la disponibilité et les autorisations ; lit les nouvelles mesures si c'est autorisé. */
    fun refreshHealthConnect() {
        viewModelScope.launch {
            val availability = healthConnect.availability()
            val granted = if (availability == HealthConnectAvailability.AVAILABLE) {
                try {
                    healthConnect.grantedPermissions().size
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    0
                }
            } else {
                0
            }
            val requested = healthConnect.permissions.size
            local.update {
                it.copy(healthConnect = it.healthConnect.copy(availability = availability, granted = granted, requested = requested))
            }
            if (granted > 0 && local.value.healthConnect.lastCount == null) syncHealthConnect()
        }
    }

    fun onHealthConnectPermissionsResult(granted: Set<String>) {
        local.update { it.copy(healthConnect = it.healthConnect.copy(granted = granted.intersect(healthConnect.permissions).size)) }
        if (granted.isNotEmpty()) syncHealthConnect()
    }

    fun syncHealthConnect() {
        if (local.value.healthConnect.syncing) return
        if (local.value.healthConnect.availability != HealthConnectAvailability.AVAILABLE) return
        local.update { it.copy(healthConnect = it.healthConnect.copy(syncing = true)) }
        viewModelScope.launch {
            try {
                val saved = settings.flow.first()
                val today = LocalDate.now()
                // On relit 2 jours avant la dernière lecture (données arrivées en retard), 30 jours la première fois.
                val from = saved.lastHealthConnectSyncedDate?.let(LocalDate::parse)?.minusDays(2)
                    ?: today.minusDays(HEALTH_CONNECT_FIRST_SYNC_DAYS)
                // Sans bracelet Polar enregistré, la montre le remplace (FC, pas, sommeil, séances).
                val count = healthConnect.sync(dao, from, replaceBand = saved.device == null)
                settings.saveHealthConnectSync(LocalDateTime.now().format(SYNC_TIME_FORMAT), today.toString())
                local.update { it.copy(healthConnect = it.healthConnect.copy(lastCount = count)) }
                if (count > 0) onDataChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Lecture Health Connect impossible", e)
            } finally {
                local.update { it.copy(healthConnect = it.healthConnect.copy(syncing = false)) }
            }
        }
    }

    /** « Continuer sans bracelet » à la première ouverture : montre et balance via Health Connect ou l'archive Withings. */
    fun skipOnboarding(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.markOnboardingDone()
            onDone()
        }
    }

    /** Choisit la séance affichée par l'écran de détail. */
    fun openSession(path: String) {
        if (selectedSession.value != path) routeAccess.value = RouteAccess.IDLE
        selectedSession.value = path
    }

    /**
     * « Autoriser l'itinéraire » : [launchConsent] ouvre la demande d'accès de Health Connect pour la séance.
     */
    fun requestRoute(launchConsent: (healthConnectId: String) -> Unit) {
        val path = selectedSession.value ?: return
        viewModelScope.launch {
            val route = dao.trainingRouteNow(path) ?: return@launch
            val id = route.healthConnectId ?: return@launch
            routeAccess.value = RouteAccess.LOADING
            // Tracé fictif d'une ancienne version (mode démo, retiré) : il n'existe pas dans Health Connect.
            if (id.startsWith(LEGACY_DEMO_ROUTE_PREFIX)) {
                routeAccess.value = RouteAccess.DENIED
            } else {
                launchConsent(id)
            }
        }
    }

    /** Réponse de Health Connect : le tracé autorisé, ou `null` si l'utilisateur a refusé. */
    fun onRouteConsentResult(healthConnectId: String, route: ExerciseRoute?) {
        if (route == null) {
            routeAccess.value = RouteAccess.DENIED
            return
        }
        viewModelScope.launch {
            try {
                val ok = healthConnect.importRoute(dao, healthConnectId, route)
                if (ok) onDataChanged()
                routeAccess.value = if (ok) RouteAccess.IDLE else RouteAccess.DENIED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                routeAccess.value = RouteAccess.IDLE
                report("Lecture du tracé impossible", e)
            }
        }
    }

    fun healthConnectManageIntent() = healthConnect.manageDataIntent()

    private fun sessionDetailFlow(path: String): Flow<SessionDetailUiState> {
        // L'analyse du tracé ne dépend que des points : elle n'est refaite que s'ils changent.
        val edit = dao.activityEdits().map { edits -> edits.firstOrNull { it.sessionPath == path } }.distinctUntilChanged()
        // Le rognage choisi par l'utilisateur s'applique aux points avant l'analyse.
        val analysis = combine(dao.trainingRoute(path).distinctUntilChanged(), edit) { route, e ->
            Triple(
                route,
                route?.takeIf { it.status == RouteStatus.AVAILABLE.name }
                    ?.let { RouteAnalysis.analyze(trimRoute(RouteCodec.decode(it.points), e?.trimStartSec, e?.trimEndSec)) },
                e,
            )
        }.flowOn(Dispatchers.Default)
        val profile = settings.flow.map { it.profile }.distinctUntilChanged()
        return combine(dao.trainingSession(path), analysis, profile, routeAccess) { raw, (route, stats, e), profile, access ->
            val session = raw?.takeIf { e?.deleted != true }?.withEdit(e)
            val date = session?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
            val maxHr = profile?.maxHr?.takeIf { it > 0 } ?: profile?.let { 220 - it.ageOn(date) }
            SessionDetailUiState(
                path = path,
                session = session,
                routeStatus = route?.let { r -> RouteStatus.entries.firstOrNull { it.name == r.status } },
                routeSource = route?.let { r -> RouteSource.entries.firstOrNull { it.name == r.source } },
                stats = stats,
                maxHr = maxHr,
                maxHrEstimated = profile?.maxHr == null,
                zones = if (stats != null && stats.hasHeartRate && maxHr != null) RouteAnalysis.hrZones(stats.points, maxHr) else emptyList(),
                sport = session?.let { Sport.of(it.sportId, it.name) },
                terrain = Terrain.named(e?.terrain),
                notes = e?.notes,
                access = access,
                loaded = true,
            )
        }
    }

    // ----- Activités : lancer, reconnaître, modifier -----

    private val editsByPath: Flow<Map<String, ActivityEditEntity>> =
        dao.activityEdits().map { edits -> edits.associateBy { it.sessionPath } }.distinctUntilChanged()

    private fun Flow<List<TrainingSessionEntity>>.edited(): Flow<List<TrainingSessionEntity>> =
        combine(this, editsByPath) { sessions, edits -> applyEdits(sessions, edits) }

    /** Activité en cours (GPS du téléphone), signal avant le départ et activité terminée à enregistrer. */
    val recording: StateFlow<RecordingState> = recorder.state
    val gpsPreview: StateFlow<GpsPreview> = recorder.preview
    val finishedRecording: StateFlow<RecordedActivity?> = recorder.finished
    val hasBarometer: Boolean get() = recorder.hasBarometer

    fun hasLocationPermission(): Boolean = recorder.hasLocationPermission()
    fun gpsEnabled(): Boolean = recorder.gpsEnabled()
    fun startGpsPreview() = recorder.startPreview()
    fun stopGpsPreview() = recorder.stopPreview()
    fun recordingTrack(): List<RoutePoint> = recorder.trackPoints()
    fun pauseRecording() = recorder.pause()
    fun resumeRecording() = recorder.resume()
    fun finishRecording() = recorder.finish()

    /** Démarre l'enregistrement ; les zones cardiaques suivent la FC max du [profile] (sinon 220 − âge). */
    fun startRecording(sport: Sport, profile: UserProfile?) {
        recorder.start(sport, HrZones.maxHr(profile?.maxHr, profile?.ageOn(LocalDate.now())))
    }

    /** Supprime l'activité terminée sans l'enregistrer. */
    fun discardRecording() = recorder.clear()

    /** Enregistre l'activité terminée : séance, tracé GPS, terrain et ressenti. [onSaved] reçoit son chemin. */
    fun saveRecording(terrain: Terrain?, feel: Int?, onSaved: (String) -> Unit) {
        val activity = recorder.finished.value ?: return
        viewModelScope.launch {
            try {
                val profile = settings.flow.first().profile
                val start = Instant.ofEpochMilli(activity.startMs).atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0)
                val path = "$PHONE_SESSION_PREFIX${activity.startMs}"
                val session = TrainingSessionEntity(
                    path = path,
                    date = start.toLocalDate().toString(),
                    startTime = start.toString(),
                    durationSec = activity.durationSec,
                    sportId = activity.sport.sportId,
                    name = defaultSessionName(activity.sport, start.toLocalTime()),
                    calories = CalorieEstimate.kcal(activity.sport, activity.distanceMeters, activity.durationSec.toDouble(), profile?.weightKg),
                    distanceMeters = activity.distanceMeters.toFloat(),
                    averageHr = activity.averageHr,
                    maxHr = activity.maxHr,
                    cardioLoad = null,
                    muscleLoad = null,
                    hrZoneSec = if (activity.averageHr != null) activity.hrZoneSec.joinToString(",") else "",
                    exerciseCount = 0,
                    summaryProto = null,
                )
                dao.upsertTrainingSessions(listOf(session))
                if (activity.points.size >= RouteAnalysis.MIN_POINTS) {
                    dao.upsertTrainingRoutes(
                        listOf(
                            TrainingRouteEntity(
                                sessionPath = path,
                                status = RouteStatus.AVAILABLE.name,
                                source = RouteSource.PHONE.name,
                                healthConnectId = null,
                                points = RouteCodec.encode(activity.points),
                            ),
                        ),
                    )
                }
                if (terrain != null || feel != null) {
                    dao.upsertActivityEdits(listOf(ActivityEditEntity(sessionPath = path, terrain = terrain?.name, feel = feel)))
                }
                recorder.clear()
                onDataChanged()
                onSaved(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Enregistrement de la séance impossible", e)
            }
        }
    }

    /** Dernières séances et activité détectée la plus récente, sur aujourd'hui et hier. */
    val activityHome: StateFlow<ActivityHomeUiState> = run {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val detected = combine(
            dao.activitySamplesRange(yesterday.toString(), today.toString()),
            dao.trainingSessionsRange(yesterday.toString(), today.toString()),
            editsByPath,
        ) { samples, sessions, edits ->
            // Quelques minutes de recul : un effort encore en cours n'est pas proposé avant d'être fini.
            val limit = LocalDateTime.now().minusMinutes(ActivityDetection.MAX_GAP_MINUTES + 1L)
            listOf(today, yesterday)
                .flatMap { day -> detectActivities(day, samples.filter { it.date == day.toString() }, sessions, edits.keys) }
                .filter { it.start.plusSeconds(it.durationSec.toLong()) <= limit }
                .maxByOrNull { it.start }
        }.flowOn(Dispatchers.Default)
        combine(
            dao.recentTrainingSessions(RECENT_SESSIONS * 3),
            editsByPath,
            detected,
            settings.flow.map { it.activityDetection }.distinctUntilChanged(),
        ) { sessions, edits, found, enabled ->
            ActivityHomeUiState(
                recent = applyEdits(sessions, edits).take(RECENT_SESSIONS).map { session ->
                    SessionItem(
                        session = session,
                        sport = Sport.of(session.sportId, session.name),
                        terrain = Terrain.named(edits[session.path]?.terrain),
                        detected = session.path.startsWith(DetectedActivity.PATH_PREFIX),
                    )
                },
                detected = found.takeIf { enabled },
                detectionEnabled = enabled,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityHomeUiState())
    }

    /** Carrés explorés par l'ensemble des tracés (rognés comme dans le détail des séances). */
    val exploration: StateFlow<ExplorationUiState> = combine(dao.availableTrainingRoutes(), editsByPath) { routes, edits ->
        val tracks = routes
            .map { route -> edits[route.sessionPath].let { e -> trimRoute(RouteCodec.decode(route.points), e?.trimStartSec, e?.trimEndSec) } }
            .filter { it.isNotEmpty() }
        ExplorationUiState(grid = Exploration.explore(tracks), routes = tracks.size, loaded = true)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExplorationUiState())

    fun setActivityDetection(enabled: Boolean) {
        viewModelScope.launch { settings.setActivityDetection(enabled) }
    }

    /** « Ce n'était pas une activité » : la proposition ne reviendra pas. */
    fun dismissDetected(activity: DetectedActivity) {
        viewModelScope.launch {
            dao.upsertActivityEdits(listOf(ActivityEditEntity(sessionPath = activity.path, deleted = true)))
            onDataChanged()
        }
    }

    /** Ajoute l'activité détectée aux séances, avec le sport confirmé ou corrigé par l'utilisateur. */
    fun confirmDetected(activity: DetectedActivity, sport: Sport, onSaved: () -> Unit) {
        viewModelScope.launch {
            try {
                val profile = settings.flow.first().profile
                val end = activity.start.plusSeconds(activity.durationSec.toLong())
                val heartRate = heartRateBetween(
                    dao.hrBlocksOn(activity.start.toLocalDate().toString()),
                    activity.start.toLocalTime(),
                    if (end.toLocalDate() == activity.start.toLocalDate()) end.toLocalTime() else LocalTime.MAX,
                )
                // À vélo, les pas ne disent rien de la distance.
                val distance = if (sport == Sport.BIKE) null else ActivityDetection.distanceMeters(activity.steps, sport, profile?.heightCm)
                dao.upsertTrainingSessions(
                    listOf(
                        TrainingSessionEntity(
                            path = activity.path,
                            date = activity.start.toLocalDate().toString(),
                            startTime = activity.start.toString(),
                            durationSec = activity.durationSec,
                            sportId = sport.sportId,
                            name = defaultSessionName(sport, activity.start.toLocalTime()),
                            calories = distance?.let { CalorieEstimate.kcal(sport, it, activity.durationSec.toDouble(), profile?.weightKg) },
                            distanceMeters = distance?.toFloat(),
                            averageHr = heartRate?.first,
                            maxHr = heartRate?.second,
                            cardioLoad = null,
                            muscleLoad = null,
                            hrZoneSec = "",
                            exerciseCount = 0,
                            summaryProto = null,
                        ),
                    ),
                )
                onDataChanged()
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Ajout de l'activité impossible", e)
            }
        }
    }

    /** Séance ouverte dans « Modifier l'activité » (la même que le détail). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activityEdit: StateFlow<ActivityEditUiState> = selectedSession
        .flatMapLatest { path ->
            if (path == null) {
                flowOf(ActivityEditUiState())
            } else {
                val route = dao.trainingRoute(path).distinctUntilChanged().map { route ->
                    val points = route?.takeIf { it.status == RouteStatus.AVAILABLE.name }?.let { RouteCodec.decode(it.points) }.orEmpty()
                    val stats = RouteAnalysis.analyze(points)
                    val altitudes = stats?.points?.mapNotNull { it.altitude?.toFloat() }.orEmpty()
                    val step = maxOf(1, altitudes.size / EDIT_PROFILE_POINTS)
                    stats?.durationSec?.toInt() to altitudes.filterIndexed { i, _ -> i % step == 0 }
                }.flowOn(Dispatchers.Default)
                combine(dao.trainingSession(path), editsByPath, route) { raw, edits, (duration, profile) ->
                    val edit = edits[path]
                    val session = raw?.takeIf { edit?.deleted != true }?.withEdit(edit)
                    ActivityEditUiState(
                        path = path,
                        session = session,
                        edit = edit,
                        sport = session?.let { Sport.of(it.sportId, it.name) },
                        routeDurationSec = duration?.takeIf { it > 0 },
                        profile = profile,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityEditUiState())

    /** Enregistre les corrections d'une séance ; un rognage recalcule durée et distance d'après le tracé gardé. */
    fun saveActivityEdit(path: String, form: ActivityEditForm, onSaved: () -> Unit) {
        viewModelScope.launch {
            try {
                val previous = dao.activityEditNow(path)
                val trimmed = form.trimStartSec > 0 || form.trimEndSec > 0
                val stats = if (trimmed) {
                    withContext(Dispatchers.Default) {
                        dao.trainingRouteNow(path)
                            ?.takeIf { it.status == RouteStatus.AVAILABLE.name }
                            ?.let { RouteAnalysis.analyze(trimRoute(RouteCodec.decode(it.points), form.trimStartSec, form.trimEndSec)) }
                    }
                } else {
                    null
                }
                dao.upsertActivityEdits(
                    listOf(
                        (previous ?: ActivityEditEntity(sessionPath = path)).copy(
                            name = form.name.trim().takeIf { it.isNotEmpty() },
                            sport = form.sport?.name,
                            terrain = form.terrain?.takeIf { form.sport == Sport.RUN }?.name,
                            notes = form.notes.trim().takeIf { it.isNotEmpty() },
                            trimStartSec = form.trimStartSec.takeIf { stats != null && it > 0 },
                            trimEndSec = form.trimEndSec.takeIf { stats != null && it > 0 },
                            trimmedDurationSec = stats?.durationSec?.toInt(),
                            trimmedDistanceMeters = stats?.distanceMeters?.toFloat(),
                            deleted = false,
                        ),
                    ),
                )
                onDataChanged()
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Modification impossible", e)
            }
        }
    }

    /**
     * Supprime une séance. Elle est retirée de la base, et une marque reste pour qu'une prochaine synchronisation
     * du bracelet ou de Health Connect, ou la détection automatique, ne la ramène pas.
     */
    fun deleteSession(path: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                dao.deleteTrainingRoute(path)
                dao.deleteTrainingExercises(path)
                dao.deleteTrainingSession(path)
                if (path.startsWith(PHONE_SESSION_PREFIX)) {
                    // Une séance enregistrée par le téléphone ne peut pas revenir : aucune marque à garder.
                    dao.deleteActivityEdit(path)
                } else {
                    dao.upsertActivityEdits(listOf(ActivityEditEntity(sessionPath = path, deleted = true)))
                }
                onDataChanged()
                onDeleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Suppression impossible", e)
            }
        }
    }

    /** « Tout effacer » : base vide, fin du mode démo (la sauvegarde reprendra avec les prochaines vraies données). */
    fun clearData() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { database.clearAllTables() }
                settings.setDemoMode(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Effacement impossible", e)
            }
        }
    }

    /** Écrit toutes les données dans le fichier [uri] choisi par l'utilisateur (archive ZIP : JSON et GPX). */
    fun exportData(uri: Uri) {
        if (local.value.exporting) return
        local.update { it.copy(exporting = true, message = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val saved = settings.flow.first()
                    val output = contentResolver.openOutputStream(uri, "wt")
                        ?: throw java.io.IOException("fichier inaccessible")
                    output.use { exporter.export(it, saved) }
                }
                local.update { it.copy(message = "Données exportées") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Export impossible", e)
            } finally {
                local.update { it.copy(exporting = false) }
            }
        }
    }

    /** Sauvegarde choisie dans le sélecteur de fichiers : l'import attend la confirmation de l'utilisateur. */
    fun onImportFilePicked(uri: Uri) {
        if (local.value.importing) return
        viewModelScope.launch {
            // Archive d'export Withings : elle n'ajoute que ce qui manque, l'import part sans autre confirmation.
            if (withingsImporter.isWithingsArchive(contentResolver, uri)) {
                if (local.value.syncing) {
                    local.update { it.copy(message = "Attends la fin de la synchronisation pour importer ton historique Withings") }
                } else {
                    runImport {
                        // Données fictives d'une ancienne version (mode démo) : elles prendraient la place des vrais
                        // jours, l'import n'ajoutant que ce qui manque. On les efface d'abord.
                        if (settings.flow.first().demoMode) {
                            withContext(Dispatchers.IO) { database.clearAllTables() }
                            settings.setDemoMode(false)
                        }
                        withingsImporter.import(contentResolver, uri).message()
                    }
                }
                return@launch
            }
            // Fichier illisible : l'import le dira avec son message habituel.
            val encrypted = runCatching { importer.isEncrypted(contentResolver, uri) }.getOrDefault(false)
            local.update {
                it.copy(pendingImport = uri, pendingImportEncrypted = encrypted, importPassphraseError = null, message = null)
            }
        }
    }

    fun cancelImport() {
        local.update { it.copy(pendingImport = null, pendingImportEncrypted = false, importPassphraseError = null) }
    }

    /**
     * Importe la sauvegarde choisie : fusion avec les données du téléphone, sans doublon. Pour une sauvegarde
     * chiffrée, [passphrase] (effacée ici) est vérifiée d'abord : si elle est fausse, le dialogue reste ouvert avec
     * « Phrase de passe incorrecte. ». La clé n'est dérivée qu'une fois pour les deux lectures de l'import.
     */
    fun confirmImport(passphrase: CharArray? = null) {
        val current = local.value
        val uri = current.pendingImport
        if (uri == null || current.importing || current.importChecking) {
            passphrase?.wipe()
            return
        }
        if (current.syncing) {
            passphrase?.wipe()
            local.update { it.copy(pendingImport = null, message = "Attends la fin de la synchronisation pour importer une sauvegarde") }
            return
        }
        if (!current.pendingImportEncrypted) {
            passphrase?.wipe()
            runImport { importer.import(contentResolver, uri).message() }
            return
        }
        if (passphrase == null) return
        local.update { it.copy(importChecking = true, importPassphraseError = null) }
        viewModelScope.launch {
            val key = try {
                val header = importer.readEncryptedHeader(contentResolver, uri)
                withContext(Dispatchers.Default) {
                    val key = BackupCrypto.deriveKey(passphrase, header)
                    if (BackupCrypto.matches(key, header)) key else null.also { key.close() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(importChecking = false, pendingImport = null, pendingImportEncrypted = false) }
                if (e is BackupFormatException) local.update { it.copy(message = e.message) } else report("Import impossible", e)
                return@launch
            } finally {
                passphrase.wipe()
            }
            if (key == null) {
                local.update { it.copy(importChecking = false, importPassphraseError = BackupCrypto.WRONG_PASSPHRASE_MESSAGE) }
                return@launch
            }
            local.update { it.copy(importChecking = false) }
            runImport { key.use { importer.importEncrypted(contentResolver, uri, it) }.message() }
        }
    }

    /** Lance un import (sauvegarde ou archive Withings) ; [import] rend le message à montrer une fois terminé. */
    private fun runImport(import: suspend () -> String) {
        local.update {
            it.copy(pendingImport = null, pendingImportEncrypted = false, importPassphraseError = null, importing = true, message = null)
        }
        viewModelScope.launch {
            try {
                val message = import()
                onDataChanged()
                local.update { it.copy(message = message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WrongPassphraseException) {
                // Vérifiée juste avant : le fichier a été remplacé entre-temps.
                local.update { it.copy(message = "Le fichier a changé pendant l'import. Rien n'a été modifié : réessaie.") }
            } catch (e: BackupFormatException) {
                local.update { it.copy(message = e.message) }
            } catch (e: Exception) {
                report("Import impossible", e)
            } catch (e: StackOverflowError) {
                // Fichier fabriqué ou très endommagé : la transaction est annulée, rien n'a été modifié.
                local.update { it.copy(message = IMPORT_TOO_LARGE_MESSAGE) }
            } catch (e: OutOfMemoryError) {
                local.update { it.copy(message = IMPORT_TOO_LARGE_MESSAGE) }
            } finally {
                local.update { it.copy(importing = false) }
            }
        }
    }

    /**
     * Activation de la sauvegarde automatique, avant le choix du dossier. L'intention est enregistrée avant d'ouvrir
     * le sélecteur, pour survivre à l'arrêt du processus pendant qu'il est affiché : avec [passphrase] (effacée ici),
     * la clé est d'abord dérivée et enregistrée, enveloppée par le Keystore, puis l'activation chiffrée est marquée en
     * attente ; sans, la clé éventuelle est effacée et l'activation en clair est marquée. L'écran ouvre ensuite le
     * sélecteur ([DeviceUiState.backupFolderPickRequested]).
     */
    fun startEnableBackup(passphrase: CharArray?) {
        if (local.value.savingBackupKey) {
            passphrase?.wipe()
            return
        }
        local.update { it.copy(savingBackupKey = passphrase != null, message = null) }
        viewModelScope.launch {
            try {
                settings.clearPendingBackupEnable()
                if (passphrase != null) {
                    withContext(Dispatchers.Default) {
                        try {
                            BackupCrypto.deriveKey(passphrase)
                        } finally {
                            passphrase.wipe()
                        }
                    }.use { key -> withContext(Dispatchers.IO) { backupKeys.save(key) } }
                    settings.savePendingBackupEnable(PendingBackupEnable.ENCRYPTED)
                } else {
                    withContext(Dispatchers.IO) { backupKeys.clear() }
                    settings.savePendingBackupEnable(PendingBackupEnable.PLAIN)
                }
                local.update { it.copy(backupKeyAvailable = true, backupFolderPickRequested = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Activation de la sauvegarde impossible", e)
            } finally {
                passphrase?.wipe()
                local.update { it.copy(savingBackupKey = false) }
            }
        }
    }

    /** « Choisir un autre dossier » (sauvegarde déjà active) : aucune activation en attente ne doit s'y appliquer. */
    fun chooseBackupFolder() {
        viewModelScope.launch {
            try {
                settings.clearPendingBackupEnable()
                local.update { it.copy(backupFolderPickRequested = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Dossier de sauvegarde inutilisable", e)
            }
        }
    }

    /** Le sélecteur de dossier demandé par [DeviceUiState.backupFolderPickRequested] vient d'être ouvert. */
    fun onBackupFolderPickLaunched() {
        local.update { it.copy(backupFolderPickRequested = false) }
    }

    /**
     * Sélecteur de dossier fermé sans choix : l'activation en attente est abandonnée et la clé enregistrée pour elle
     * effacée. D'après l'état enregistré seulement : le processus a pu être tué pendant que le sélecteur était ouvert.
     */
    fun onBackupFolderPickCancelled() {
        viewModelScope.launch {
            try {
                val backup = settings.flow.first().backup
                if (backup.pendingEnable == null) return@launch
                settings.clearPendingBackupEnable()
                if (discardKeyOnPickCancelled(backup.enabled, backup.pendingEnable)) {
                    withContext(Dispatchers.IO) { backupKeys.clear() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Annulation de l'activation incomplète", e)
            }
        }
    }

    /**
     * Dossier choisi pour la sauvegarde automatique : activation (selon l'intention enregistrée avant l'ouverture du
     * sélecteur) ou changement de dossier, puis sauvegarde aussitôt. Seul l'état enregistré compte, jamais la mémoire
     * du ViewModel, qui a pu être recréé entre-temps : sans sauvegarde active ni activation en attente, rien n'est
     * activé ; une activation chiffrée dont la clé manque n'est pas faite en clair.
     */
    fun onBackupFolderPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                val backup = settings.flow.first().backup
                val keyUsable = backup.encryption?.let { enc -> withContext(Dispatchers.IO) { backupKeys.canUnlock(enc) } } ?: false
                when (decideFolderPick(backup.enabled, backup.pendingEnable, keyUsable)) {
                    FolderPickAction.IGNORE -> {
                        withContext(Dispatchers.IO) { releaseFolder(uri.toString()) }
                        local.update { it.copy(message = "Activation de la sauvegarde interrompue : recommence.") }
                        return@launch
                    }
                    FolderPickAction.KEY_MISSING -> {
                        settings.clearPendingBackupEnable()
                        withContext(Dispatchers.IO) {
                            backupKeys.clear()
                            releaseFolder(uri.toString())
                        }
                        local.update {
                            it.copy(message = "Clé de chiffrement introuvable : rien n'a été activé. Recommence l'activation.")
                        }
                        return@launch
                    }
                    FolderPickAction.ACTIVATE -> {
                        // Activation en clair choisie : aucune clé ne doit rester d'une tentative précédente.
                        if (backup.pendingEnable == PendingBackupEnable.PLAIN && backup.encryption != null) {
                            withContext(Dispatchers.IO) { backupKeys.clear() }
                        }
                        local.update { it.copy(backupKeyAvailable = true) }
                    }
                    FolderPickAction.CHANGE_FOLDER -> Unit
                }
                withContext(Dispatchers.IO) {
                    contentResolver.takePersistableUriPermission(uri, BackupFolder.PERSISTABLE_FLAGS)
                    val previous = backup.folderUri
                    val name = BackupFolder.displayName(contentResolver, uri)
                    // Termine aussi l'activation en attente.
                    settings.saveBackupFolder(uri.toString(), name)
                    if (previous != null && previous != uri.toString()) releaseFolder(previous)
                }
                local.update { it.copy(backupFolderAccessible = true) }
                autoBackup.schedulePeriodic()
                autoBackup.requestNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Dossier de sauvegarde inutilisable", e)
            }
        }
    }

    /** Désactive la sauvegarde automatique ; les sauvegardes déjà écrites restent dans le dossier. La clé est effacée. */
    fun disableBackup() {
        viewModelScope.launch {
            try {
                autoBackup.cancelAll()
                val previous = settings.flow.first().backup.folderUri
                settings.clearBackupFolder()
                withContext(Dispatchers.IO) {
                    backupKeys.clear()
                    previous?.let { releaseFolder(it) }
                }
                local.update { it.copy(backupFolderAccessible = true, backupKeyAvailable = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Désactivation de la sauvegarde impossible", e)
            }
        }
    }

    /**
     * Chiffre les prochaines sauvegardes avec [passphrase] (effacée ici) : activation du chiffrement, nouvelle phrase
     * de passe ou phrase ressaisie après un transfert. Les sauvegardes déjà écrites gardent leur phrase de passe.
     */
    fun setBackupPassphrase(passphrase: CharArray) {
        if (local.value.savingBackupKey) {
            passphrase.wipe()
            return
        }
        local.update { it.copy(savingBackupKey = true, message = null) }
        viewModelScope.launch {
            try {
                val wasEncrypted = settings.flow.first().backup.encryption != null
                withContext(Dispatchers.Default) {
                    try {
                        BackupCrypto.deriveKey(passphrase)
                    } finally {
                        passphrase.wipe()
                    }
                }.use { key -> withContext(Dispatchers.IO) { backupKeys.save(key) } }
                local.update {
                    it.copy(
                        backupKeyAvailable = true,
                        message = if (wasEncrypted) {
                            "Phrase de passe enregistrée : les prochaines sauvegardes l'utiliseront. Les précédentes " +
                                "gardent celle avec laquelle elles ont été chiffrées."
                        } else {
                            "Les prochaines sauvegardes seront chiffrées. Celles déjà écrites en clair restent " +
                                "lisibles jusqu'à leur remplacement par la rotation."
                        },
                    )
                }
                if (settings.flow.first().backup.enabled) autoBackup.requestNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Chiffrement impossible", e)
            } finally {
                local.update { it.copy(savingBackupKey = false) }
            }
        }
    }

    /** Les prochaines sauvegardes seront en clair ; celles déjà chiffrées le restent. */
    fun disableBackupEncryption() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { backupKeys.clear() }
                local.update {
                    it.copy(
                        backupKeyAvailable = true,
                        message = "Chiffrement désactivé : les prochaines sauvegardes seront en clair. Celles déjà " +
                            "chiffrées le restent, avec leur phrase de passe.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Désactivation du chiffrement impossible", e)
            }
        }
    }

    fun backupNow() {
        viewModelScope.launch {
            try {
                val saved = settings.flow.first()
                if (saved.demoMode) {
                    local.update { it.copy(message = BACKUP_DEMO_MESSAGE) }
                } else if (saved.backup.enabled) {
                    autoBackup.requestNow()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Sauvegarde impossible", e)
            }
        }
    }

    /**
     * Vérifie, au retour dans l'app, que l'accès au dossier de sauvegarde est toujours accordé et que la clé des
     * sauvegardes chiffrées se relit sur ce téléphone (elle ne survit pas à un transfert d'appareil).
     */
    fun refreshBackupFolder() {
        viewModelScope.launch {
            try {
                val backup = settings.flow.first().backup
                val uri = backup.folderUri
                val accessible = uri == null || withContext(Dispatchers.IO) {
                    runCatching { BackupFolder.hasPermission(contentResolver, uri.toUri()) }.getOrDefault(false)
                }
                val keyAvailable = backup.encryption?.let { enc -> withContext(Dispatchers.IO) { backupKeys.canUnlock(enc) } } ?: true
                local.update { it.copy(backupFolderAccessible = accessible, backupKeyAvailable = keyAvailable) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Vérification de la sauvegarde impossible", e)
            }
        }
    }

    private fun releaseFolder(uri: String) {
        // Permission déjà perdue (ou jamais accordée sur ce téléphone) : rien à libérer.
        runCatching { contentResolver.releasePersistableUriPermission(uri.toUri(), BackupFolder.PERSISTABLE_FLAGS) }
    }

    /** Les données ont changé hors synchro du bracelet (Health Connect, import, tracé) : à sauvegarder. */
    private suspend fun onDataChanged() {
        settings.markDataChanged()
        requestBackupAfterChange()
    }

    private suspend fun requestBackupAfterChange() {
        if (settings.flow.first().backup.enabled) autoBackup.requestAfterChange()
    }

    /** Nom proposé pour le fichier d'export. */
    fun exportFileName(): String = DataExporter.archiveName(LocalDate.now())

    fun dismissMessage() {
        local.update { it.copy(message = null) }
    }

    /** Règle les objectifs quotidiens à partir d'aujourd'hui. */
    fun setGoals(goals: DailyGoals) {
        viewModelScope.launch { settings.saveGoals(goals, LocalDate.now()) }
    }

    fun previousDay() = selectedDate.update { it.minusDays(1) }

    /** Affiche [date] (jamais après aujourd'hui). */
    fun selectDate(date: LocalDate) {
        val today = LocalDate.now()
        selectedDate.value = if (date > today) today else date
    }

    fun completeOnboarding() {
        viewModelScope.launch { settings.markOnboardingDone() }
    }

    fun nextDay() = selectedDate.update { if (it < LocalDate.now()) it.plusDays(1) else it }

    private fun connect(deviceId: String) {
        try {
            gateway.connect(deviceId)
        } catch (e: Exception) {
            report("Connexion impossible", e)
        }
    }

    private suspend fun checkSetup() {
        val deviceId = gateway.state.value.deviceId ?: return
        try {
            val done = deviceMutex.withLock { gateway.api.isFtuDone(deviceId) }
            local.update { it.copy(setupDone = done) }
            if (done) {
                // Le profil vit sur le bracelet : on le recopie pour les calculs (âge, sexe, FC max).
                val physical = deviceMutex.withLock { gateway.api.getUserPhysicalConfiguration(deviceId) }
                if (physical != null) {
                    settings.saveProfile(
                        UserProfile(
                            female = physical.gender == PolarFirstTimeUseConfig.Gender.FEMALE,
                            birthDate = physical.birthDate,
                            heightCm = physical.height,
                            weightKg = physical.weight,
                            maxHr = physical.maxHeartRate,
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Lecture de la configuration impossible", e)
        }
    }

    private fun report(prefix: String, e: Exception) {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
        local.update { it.copy(message = "$prefix : $detail") }
    }

    private fun dayFlow(date: LocalDate): Flow<DayUiState> {
        val key = date.toString()
        val activity = combine(
            dao.dailyActivity(key),
            dao.activitySamples(key),
            dao.hrBlocks(key),
            dao.ppiBlocks(key),
        ) { daily, samples, hr, ppi ->
            DayUiState(
                date = date,
                activity = daily,
                minuteStepBlocks = minuteStepBlocks(samples),
                heartRate = heartRateStats(hr),
                ppiIntervalCount = ppiIntervalCount(ppi),
                hourlyHeartRate = hourlyHeartRate(hr),
            )
        }
        val night = combine(
            dao.sleep(key),
            dao.nightlyRecharge(key),
            dao.skinTemperature(key),
            dao.trainingSessions(key).edited(),
        ) { sleep, recharge, temperature, sessions ->
            DayUiState(
                date = date,
                sleep = sleep?.let(::sleepStats),
                recharge = recharge,
                skinTemperature = temperature?.let(::temperatureStats),
                sessions = sessions,
            )
        }
        // Les scores comparent la nuit à ses propres nuits précédentes : on lit donc une fenêtre de 28 jours.
        // Le Body Battery simule les 2 jours précédents et a besoin de la nuit qui commence le soir même ;
        // son stress VFC compare chaque créneau aux 7 jours qui précèdent, d'où une fenêtre PPI plus large.
        val ppiStart = date.minusDays(BodyBatteryInputs.WARMUP_DAYS + HrvStress.BASELINE_DAYS).toString()
        val baselineStart = date.minusDays(RecoveryScoring.BASELINE_WINDOW_NIGHTS.toLong()).toString()
        val batteryStart = date.minusDays(BodyBatteryInputs.WARMUP_DAYS).toString()
        val nextDay = date.plusDays(1).toString()
        val scores = combine(
            dao.sleepRange(baselineStart, nextDay),
            dao.nightlyRechargeRange(baselineStart, nextDay),
            dao.activitySamplesRange(batteryStart, key),
            dao.hrBlocksRange(batteryStart, key),
            // VFC : intervalles PPI du bracelet, ou mesures ponctuelles d'une montre via Health Connect.
            combine(
                dao.ppiBlocksRange(ppiStart, key),
                dao.externalMeasurementsOfType(MeasurementType.HRV_RMSSD.name, ppiStart, key),
            ) { ppi, hrv -> ppi to hrv },
        ) { sleeps, recharges, activitySamples, hrBlocks, (ppiBlocks, hrvMeasures) ->
            val tonight = scoreNight(key, sleeps, recharges)
            val batteryDates = (0..BodyBatteryInputs.WARMUP_DAYS + 1).map {
                date.minusDays(BodyBatteryInputs.WARMUP_DAYS).plusDays(it).toString()
            }
            val quality = batteryDates.mapNotNull { wake ->
                val scores = if (wake == key) tonight else scoreNight(wake, sleeps, recharges)
                scores.quality?.let { wake to it }
            }.toMap()
            val restingHr = BodyBatteryInputs.restingHeartRate(
                recharges.lastOrNull { it.date <= key && (it.meanRriMs ?: 0) > 0 }?.meanRriMs,
                hrBlocks,
            )
            val stress = hrvStress(ppiBlocks, hrvMeasures, sleeps, restingHr)
            val grid = BodyBatteryInputs.build(
                date = date,
                now = LocalDateTime.now(),
                activity = activitySamples,
                heartRate = hrBlocks,
                sleeps = sleeps.filter { it.date in batteryDates },
                qualityByWakeDate = quality,
                stress = stress,
            )
            val simulation = BodyBatteryModel.simulate(grid.slots, restingHr)
            val battery = BodyBatteryModel.summarize(simulation, grid.slots, grid.start, grid.dayFrom, grid.dayTo)
            DayScores(tonight.sleep, tonight.recovery, battery)
        }.flowOn(Dispatchers.Default)
        // Bien-être : stress VFC des 28 derniers jours (plus les 7 jours de sa ligne de base) et scores des
        // 7 dernières nuits, eux-mêmes comparés aux 28 nuits qui précèdent. Calcul plus long que le reste :
        // la journée s'affiche sans l'attendre.
        val trendStart = date.minusDays(Wellbeing.TREND_DAYS - 1L)
        val stressStart = trendStart.minusDays(HrvStress.BASELINE_DAYS).toString()
        val nightsStart = date.minusDays(Wellbeing.SCORE_DAYS + RecoveryScoring.BASELINE_WINDOW_NIGHTS.toLong()).toString()
        val wellbeing: Flow<WellbeingDay?> = combine(
            dao.ppiBlocksRange(stressStart, key),
            dao.externalMeasurementsOfType(MeasurementType.HRV_RMSSD.name, stressStart, key),
            dao.sleepRange(minOf(stressStart, nightsStart), key),
            dao.nightlyRechargeRange(nightsStart, key),
        ) { ppiBlocks, hrvMeasures, sleeps, recharges ->
            val restingHr = BodyBatteryInputs.restingHeartRate(
                recharges.lastOrNull { (it.meanRriMs ?: 0) > 0 }?.meanRriMs,
                emptyList(),
            )
            val nights = (0 until Wellbeing.SCORE_DAYS).map { ago ->
                val wake = date.minusDays(ago.toLong())
                val scores = scoreNight(wake.toString(), sleeps, recharges)
                WellbeingNight(
                    date = wake,
                    hrvScore = scores.recovery?.hrv,
                    rmssdMs = scores.recovery?.rmssdMs,
                    rmssdBaselineMs = scores.recovery?.rmssdBaselineMs,
                    sleepScore = scores.sleep?.total,
                )
            }
            Wellbeing.build(date, hrvStress(ppiBlocks, hrvMeasures, sleeps, restingHr), nights)
                .takeUnless { it.isEmpty }
        }.flowOn(Dispatchers.Default).onStart<WellbeingDay?> { emit(null) }
        // VO2max : profil (âge, sexe, FC max), courses des 6 dernières semaines, FC nocturne des 2 dernières.
        val vo2Max = combine(
            settings.flow.map { it.profile }.distinctUntilChanged(),
            dao.trainingSessionsRange(date.minusDays(Vo2MaxEstimation.RUN_WINDOW_DAYS).toString(), key).edited(),
            dao.nightlyRechargeRange(date.minusDays(14).toString(), key),
        ) { profile, sessions, recharges ->
            profile ?: return@combine null
            val nightlyHr = recharges.mapNotNull { r -> r.meanRriMs?.takeIf { it > 0 }?.let { 60_000.0 / it } }
            Vo2MaxEstimation.estimate(
                age = profile.ageOn(date),
                female = profile.female,
                profileMaxHr = profile.maxHr,
                restingHr = if (nightlyHr.isEmpty()) null else nightlyHr.sorted()[nightlyHr.size / 2],
                sessions = sessions,
            )
        }
        // Poids : la dernière pesée peut dater de plusieurs jours, et l'évolution remonte jusqu'à 30 jours avant.
        val body = dao.externalMeasurementsRange(date.minusDays(BodyMeasurements.HISTORY_DAYS).toString(), key)
            .map { BodyReadings(BodyMeasurements.body(it, date), BodyMeasurements.spo2(it, date), weightHistory(it, date.minusDays(30), date)) }
        val day = combine(activity, night, scores, vo2Max, body) { a, n, scores, vo2, readings ->
            a.copy(
                sleep = n.sleep,
                recharge = n.recharge,
                skinTemperature = n.skinTemperature,
                sessions = n.sessions,
                sleepScore = scores.sleep,
                recovery = scores.recovery,
                battery = scores.battery,
                vo2Max = vo2,
                body = readings.summary,
                spo2 = readings.spo2,
                weightHistory = readings.weights,
            )
        }
        return combine(day, wellbeing) { state, score -> state.copy(wellbeing = score) }
    }

    /**
     * Stress VFC par créneau d'éveil : intervalles PPI du bracelet, complétés par les mesures ponctuelles
     * de VFC d'une montre lues dans Health Connect.
     */
    private fun hrvStress(
        ppiBlocks: List<PpiBlockEntity>,
        hrvMeasures: List<ExternalMeasurementEntity>,
        sleeps: List<SleepEntity>,
        restingHr: Double,
    ): Map<LocalDateTime, Double> = HrvStress.score(
        windows = (
            HrvStress.windows(ppiBlocks) +
                HrvStress.windowsFromRmssd(
                    hrvMeasures.map { Instant.parse(it.time).atZone(ZoneId.systemDefault()).toLocalDateTime() to it.value },
                )
            ).distinctBy { it.start },
        asleep = HrvStress.sleepIntervals(sleeps),
        restingHr = restingHr,
    )

    companion object {
        private const val SCAN_TIMEOUT_MS = 30_000L
        /** Séances de la liste « Activités récentes ». */
        private const val RECENT_SESSIONS = 5
        private const val EDIT_PROFILE_POINTS = 60
        private const val FIRST_SYNC_DAYS = 14L
        private const val HEALTH_CONNECT_FIRST_SYNC_DAYS = 30L
        private const val LEGACY_DEMO_ROUTE_PREFIX = "demo-route:"
        private const val IMPORT_TOO_LARGE_MESSAGE =
            "Cette sauvegarde est trop volumineuse ou endommagée pour être importée. Rien n'a été modifié."
        private const val BACKUP_DEMO_MESSAGE =
            "Sauvegarde désactivée pendant la démo, pour ne pas remplacer tes vraies sauvegardes. " +
                "« Tout effacer » pour la reprendre."
        private val SYNC_TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        private val BIRTH_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/uuuu")

        val Factory = viewModelFactory {
            initializer { MainViewModel(this[APPLICATION_KEY] as BoucleApp) }
        }

        /** @throws IllegalArgumentException avec un message lisible si une valeur est invalide. */
        private fun ProfileForm.toConfig(): PolarFirstTimeUseConfig {
            val birth = try {
                LocalDate.parse(birthDate.trim(), BIRTH_DATE_FORMAT)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("Date de naissance attendue au format JJ/MM/AAAA.")
            }
            fun number(value: String, label: String): Float =
                value.trim().replace(',', '.').toFloatOrNull()
                    ?: throw IllegalArgumentException("$label : valeur numérique attendue.")

            return PolarFirstTimeUseConfig(
                gender = if (female) PolarFirstTimeUseConfig.Gender.FEMALE else PolarFirstTimeUseConfig.Gender.MALE,
                birthDate = birth,
                height = number(heightCm, "Taille"),
                weight = number(weightKg, "Poids"),
                maxHeartRate = number(maxHr, "FC max").toInt(),
                vo2Max = number(vo2Max, "VO2max").toInt(),
                restingHeartRate = number(restingHr, "FC de repos").toInt(),
                trainingBackground = trainingBackground,
                deviceTime = polarDeviceTime(Instant.now()),
                typicalDay = typicalDay,
                sleepGoalMinutes = (number(sleepGoalHours, "Objectif de sommeil") * 60).toInt(),
            )
        }
    }
}

private val POLAR_DEVICE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH).withZone(ZoneOffset.UTC)

/**
 * Heure attendue par `doFirstTimeUse` du SDK Polar : UTC, à la seconde, suffixe `Z` littéral.
 * Le SDK la relit avec ce motif exact ; une fraction de seconde ou un décalage `+01:00` le fait échouer.
 */
internal fun polarDeviceTime(now: Instant): String = POLAR_DEVICE_TIME.format(now)
