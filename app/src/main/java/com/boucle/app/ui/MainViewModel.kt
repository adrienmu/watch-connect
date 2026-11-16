package com.boucle.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.boucle.app.BoucleApp
import com.boucle.app.data.DemoData
import com.boucle.app.data.SavedDevice
import com.boucle.app.domain.RecoveryScoring
import com.boucle.app.domain.SleepScoring
import com.boucle.app.domain.toNight
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class FoundDevice(val id: String, val name: String, val rssi: Int)

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
)

class MainViewModel(app: BoucleApp) : ViewModel() {

    private val gateway = app.gateway
    private val settings = app.settings
    private val syncEngine = app.syncEngine
    private val database = app.database
    private val dao = database.healthDao()

    private data class Local(
        val scanning: Boolean = false,
        val found: List<FoundDevice> = emptyList(),
        val setupDone: Boolean? = null,
        val savingProfile: Boolean = false,
        val syncing: Boolean = false,
        val log: List<String> = emptyList(),
        val message: String? = null,
    )

    private val local = MutableStateFlow(Local())

    /** Le bracelet traite une commande à la fois : tout appel au SDK passe par ce verrou. */
    private val deviceMutex = Mutex()
    private var scanJob: Job? = null
    private var autoConnectDone = false

    val deviceState: StateFlow<DeviceUiState> =
        combine(gateway.state, settings.flow, local) { link, saved, local ->
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
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceUiState())

    private val selectedDate = MutableStateFlow(LocalDate.now())

    @OptIn(ExperimentalCoroutinesApi::class)
    val dayState: StateFlow<DayUiState> = selectedDate
        .flatMapLatest { dayFlow(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState(LocalDate.now()))

    init {
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
            local.update { Local() }
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
                if (ok) settings.saveSync(today.toString(), LocalDateTime.now().format(SYNC_TIME_FORMAT))
            } finally {
                local.update { it.copy(syncing = false) }
            }
        }
    }

    /** Remplace le contenu de la base par des données fictives, sans bracelet. */
    fun loadDemo(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { database.clearAllTables() }
                DemoData.load(dao)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Chargement de la démo impossible", e)
            }
        }
    }

    fun clearData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { database.clearAllTables() }
        }
    }

    fun dismissMessage() {
        local.update { it.copy(message = null) }
    }

    fun previousDay() = selectedDate.update { it.minusDays(1) }

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
            )
        }
        val night = combine(
            dao.sleep(key),
            dao.nightlyRecharge(key),
            dao.skinTemperature(key),
            dao.trainingSessions(key),
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
        val baselineStart = date.minusDays(RecoveryScoring.BASELINE_WINDOW_NIGHTS.toLong()).toString()
        val scores = combine(
            dao.sleepRange(baselineStart, key),
            dao.nightlyRechargeRange(baselineStart, key),
        ) { sleeps, recharges ->
            val tonight = sleeps.firstOrNull { it.date == key }
            val night = tonight?.toNight()
            val sleepScore = night?.let {
                SleepScoring.score(it, sleeps.filter { s -> s.date < key }.mapNotNull { s -> s.toNight() })
            }
            val rechargeTonight = recharges.firstOrNull { it.date == key }
            val recovery = rechargeTonight?.let {
                RecoveryScoring.score(
                    night = it,
                    history = recharges.filter { r -> r.date < key },
                    sleepScore = sleepScore?.total,
                    skinTemperatureDeviationC = tonight?.skinTemperatureDeviationC,
                )
            }
            sleepScore to recovery
        }
        return combine(activity, night, scores) { a, n, (sleepScore, recovery) ->
            a.copy(
                sleep = n.sleep,
                recharge = n.recharge,
                skinTemperature = n.skinTemperature,
                sessions = n.sessions,
                sleepScore = sleepScore,
                recovery = recovery,
            )
        }
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val FIRST_SYNC_DAYS = 14L
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
                deviceTime = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                typicalDay = typicalDay,
                sleepGoalMinutes = (number(sleepGoalHours, "Objectif de sommeil") * 60).toInt(),
            )
        }
    }
}
