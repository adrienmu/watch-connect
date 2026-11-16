package com.boucle.app.healthconnect

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.boucle.app.data.db.ExternalMeasurementEntity
import com.boucle.app.data.db.HealthDao
import com.boucle.app.domain.MeasurementType
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset

enum class HealthConnectAvailability { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED }

/**
 * Lecture seule dans Health Connect des données que l'app Withings y écrit.
 *
 *  - Toujours : balance (poids, composition corporelle), SpO2 et VFC de la montre, rangés comme mesures.
 *  - Sans bracelet Polar ([sync] avec `replaceBand = true`) : FC, pas, sommeil, respiration et séances de la
 *    montre, traduits dans les tables du bracelet ([HealthConnectMapping]) pour que tous les scores
 *    fonctionnent. Avec un bracelet, ces données ne sont pas lues : elles feraient doublon.
 *
 * Les données d'activité ne sont lues que depuis l'app Withings ([ACTIVITY_SOURCE]) : le téléphone écrit
 * souvent ses propres pas dans Health Connect, et les additionner compterait tout deux fois.
 */
class HealthConnectGateway(private val context: Context) {

    private val measurementPermissions = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
    )

    private val activityPermissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    /** Autorisations demandées, toutes en lecture (doivent correspondre au manifeste). */
    val permissions: Set<String> = measurementPermissions + activityPermissions

    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    private val activityOrigin = setOf(DataOrigin(ACTIVITY_SOURCE))

    fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UPDATE_REQUIRED
            else -> HealthConnectAvailability.NOT_INSTALLED
        }

    /** Ouvre le Play Store sur Health Connect (Android 13, où ce n'est pas encore une partie du système). */
    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        setPackage("com.android.vending")
        data = Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
        putExtra("overlay", true)
        putExtra("callerId", context.packageName)
    }

    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions().intersect(permissions)

    /**
     * Lit les jours à partir de [from] et les enregistre. Les données sont relues depuis la veille, pour avoir
     * en entier la nuit qui se termine le matin de [from] ; seuls les jours à partir de [from] sont écrits,
     * chacun complet (une journée partiellement lue écraserait la journée stockée).
     *
     * @param replaceBand vrai sans bracelet Polar : la montre remplace alors le bracelet.
     * @return le nombre d'éléments enregistrés.
     */
    suspend fun sync(dao: HealthDao, from: LocalDate, replaceBand: Boolean, zone: ZoneId = ZoneId.systemDefault()): Int {
        val granted = grantedPermissions()
        val since = from.minusDays(1).atStartOfDay(zone).toInstant()
        val keep = { date: String -> date >= from.toString() }

        val measurements = buildList {
            addAll(readAll<WeightRecord>(granted, since).map { measurement(it.metadata, MeasurementType.WEIGHT, it.weight.inKilograms, it.time, it.zoneOffset, zone) })
            addAll(readAll<BodyFatRecord>(granted, since).map { measurement(it.metadata, MeasurementType.BODY_FAT, it.percentage.value, it.time, it.zoneOffset, zone) })
            addAll(readAll<LeanBodyMassRecord>(granted, since).map { measurement(it.metadata, MeasurementType.LEAN_MASS, it.mass.inKilograms, it.time, it.zoneOffset, zone) })
            addAll(readAll<BoneMassRecord>(granted, since).map { measurement(it.metadata, MeasurementType.BONE_MASS, it.mass.inKilograms, it.time, it.zoneOffset, zone) })
            addAll(readAll<BodyWaterMassRecord>(granted, since).map { measurement(it.metadata, MeasurementType.WATER_MASS, it.mass.inKilograms, it.time, it.zoneOffset, zone) })
            addAll(readAll<OxygenSaturationRecord>(granted, since).map { measurement(it.metadata, MeasurementType.SPO2, it.percentage.value, it.time, it.zoneOffset, zone) })
            addAll(
                readAll<HeartRateVariabilityRmssdRecord>(granted, since).map {
                    measurement(it.metadata, MeasurementType.HRV_RMSSD, it.heartRateVariabilityMillis, it.time, it.zoneOffset, zone)
                },
            )
        }
        dao.upsertExternalMeasurements(measurements)
        if (!replaceBand) return measurements.size

        val heart = readAll<HeartRateRecord>(granted, since, activityOrigin)
            .flatMap { record -> record.samples.map { HcHeartRate(it.time, it.beatsPerMinute.toDouble()) } }
        val steps = readAll<StepsRecord>(granted, since, activityOrigin).map { HcSteps(it.startTime, it.endTime, it.count) }
        val sessions = readAll<SleepSessionRecord>(granted, since, activityOrigin).map { record ->
            HcSleepSession(
                start = record.startTime,
                end = record.endTime,
                zone = record.endZoneOffset,
                stages = record.stages.map { HcSleepStage(it.startTime, it.endTime, sleepState(it.stage)) },
            )
        }
        val hrv = measurements.filter { it.type == MeasurementType.HRV_RMSSD.name }.map { HcValue(Instant.parse(it.time), it.value) }
        val respiration = readAll<RespiratoryRateRecord>(granted, since, activityOrigin).map { HcValue(it.time, it.rate) }
        val exercises = readAll<ExerciseSessionRecord>(granted, since, activityOrigin).map { record ->
            val totals = sessionTotals(granted, record.startTime, record.endTime)
            HcExercise(
                id = record.metadata.id,
                start = record.startTime,
                end = record.endTime,
                zone = record.startZoneOffset,
                running = record.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING ||
                    record.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
                name = record.title?.takeIf { it.isNotBlank() } ?: exerciseName(record.exerciseType),
                distanceMeters = totals.first,
                calories = totals.second,
            )
        }

        val sleeps = HealthConnectMapping.sleeps(sessions, zone).filter { keep(it.date) }
        val hrBlocks = HealthConnectMapping.hrBlocks(heart, zone).filter { keep(it.date) }
        val activity = HealthConnectMapping.activitySamples(steps, zone).filter { keep(it.date) }
        val nights = HealthConnectMapping.nightlyMeasures(sleeps, heart, hrv, respiration)
        val daily = HealthConnectMapping.dailyActivity(dailyTotals(granted, from, LocalDate.now(zone)))
        val training = HealthConnectMapping.trainingSessions(exercises, heart, zone).filter { keep(it.date) }

        dao.upsertSleep(sleeps)
        dao.upsertHrBlocks(hrBlocks)
        dao.upsertActivitySamples(activity)
        dao.upsertNightlyRecharge(nights)
        dao.upsertDailyActivity(daily)
        dao.upsertTrainingSessions(training)
        return measurements.size + sleeps.size + hrBlocks.size + activity.size + nights.size + daily.size + training.size
    }

    /** Tous les enregistrements de type [T] depuis [since], page par page ; rien sans l'autorisation de lecture. */
    private suspend inline fun <reified T : Record> readAll(
        granted: Set<String>,
        since: Instant,
        origins: Set<DataOrigin> = emptySet(),
    ): List<T> {
        if (HealthPermission.getReadPermission(T::class) !in granted) return emptyList()
        val out = ArrayList<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
                    timeRangeFilter = TimeRangeFilter.after(since),
                    dataOriginFilter = origins,
                    pageToken = pageToken,
                ),
            )
            out += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return out
    }

    /** Pas, distance et calories actives par jour, dédoublonnés par Health Connect. */
    private suspend fun dailyTotals(granted: Set<String>, from: LocalDate, to: LocalDate): List<HcDayTotals> {
        val metrics = buildSet<AggregateMetric<*>> {
            if (HealthPermission.getReadPermission(StepsRecord::class) in granted) add(StepsRecord.COUNT_TOTAL)
            if (HealthPermission.getReadPermission(DistanceRecord::class) in granted) add(DistanceRecord.DISTANCE_TOTAL)
            if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in granted) {
                add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            }
        }
        if (metrics.isEmpty()) return emptyList()
        return client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = metrics,
                timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
                dataOriginFilter = activityOrigin,
            ),
        ).map { group ->
            HcDayTotals(
                date = group.startTime.toLocalDate(),
                steps = group.result[StepsRecord.COUNT_TOTAL],
                distanceMeters = group.result[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                activeCalories = group.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
            )
        }
    }

    /** Distance (m) et calories actives (kcal) d'une séance. */
    private suspend fun sessionTotals(granted: Set<String>, start: Instant, end: Instant): Pair<Double?, Double?> {
        val metrics = buildSet<AggregateMetric<*>> {
            if (HealthPermission.getReadPermission(DistanceRecord::class) in granted) add(DistanceRecord.DISTANCE_TOTAL)
            if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in granted) {
                add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            }
        }
        if (metrics.isEmpty()) return null to null
        val result = client.aggregate(
            AggregateRequest(metrics, TimeRangeFilter.between(start, end), dataOriginFilter = activityOrigin),
        )
        return result[DistanceRecord.DISTANCE_TOTAL]?.inMeters to
            result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
    }

    private fun measurement(
        metadata: Metadata,
        type: MeasurementType,
        value: Double,
        time: Instant,
        offset: ZoneOffset?,
        defaultZone: ZoneId,
    ): ExternalMeasurementEntity {
        val zone: ZoneId = offset ?: defaultZone
        return ExternalMeasurementEntity(
            id = metadata.id,
            type = type.name,
            time = time.toString(),
            date = time.atZone(zone).toLocalDate().toString(),
            value = value,
            source = metadata.dataOrigin.packageName,
        )
    }

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        /** App Withings (Health Mate), seule source lue pour l'activité, la FC et le sommeil. */
        const val ACTIVITY_SOURCE = "com.withings.wiscale2"

        /** Phase Health Connect → vocabulaire du bracelet (WAKE, NONREM12, NONREM3, REM). */
        fun sleepState(stage: Int): String = when (stage) {
            SleepSessionRecord.STAGE_TYPE_DEEP -> "NONREM3"
            SleepSessionRecord.STAGE_TYPE_REM -> "REM"
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
            -> "WAKE"
            // Léger, « endormi » sans détail ou inconnu.
            else -> "NONREM12"
        }

        private fun exerciseName(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Course à pied"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Marche"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Vélo"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Natation"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Randonnée"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Renforcement"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
            else -> "Entraînement"
        }
    }
}
