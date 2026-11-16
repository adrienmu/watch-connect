package com.boucle.app.healthconnect

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.boucle.app.data.db.ExternalMeasurementEntity
import com.boucle.app.data.db.HealthDao
import com.boucle.app.domain.MeasurementType
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

enum class HealthConnectAvailability { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED }

/**
 * Lecture seule dans Health Connect des mesures que d'autres apps y écrivent : balance Withings (poids et
 * composition corporelle) et montre Withings (SpO2). Les pas, la FC et le sommeil de la montre ne sont pas
 * lus : ils feraient doublon avec le bracelet Polar.
 */
class HealthConnectGateway(private val context: Context) {

    /** Autorisations demandées, toutes en lecture (doivent correspondre au manifeste). */
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    )

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

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
     * Lit les nouvelles mesures et les enregistre. On reprend 2 jours avant la dernière mesure connue (une
     * pesée peut arriver en retard dans Health Connect), ou 30 jours en arrière la première fois — Health
     * Connect ne donne de toute façon pas plus ancien sans autorisation d'historique.
     *
     * @return le nombre de mesures lues.
     */
    suspend fun sync(dao: HealthDao, now: Instant = Instant.now()): Int {
        val granted = grantedPermissions()
        val since = dao.latestExternalMeasurementTime()
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?.minus(2, ChronoUnit.DAYS)
            ?: now.minus(FIRST_SYNC_DAYS, ChronoUnit.DAYS)
        val items = buildList {
            addAll(
                readIfGranted<WeightRecord>(granted, since) {
                    measurement(it.metadata, MeasurementType.WEIGHT, it.weight.inKilograms, it.time, it.zoneOffset)
                },
            )
            addAll(
                readIfGranted<BodyFatRecord>(granted, since) {
                    measurement(it.metadata, MeasurementType.BODY_FAT, it.percentage.value, it.time, it.zoneOffset)
                },
            )
            addAll(
                readIfGranted<LeanBodyMassRecord>(granted, since) {
                    measurement(it.metadata, MeasurementType.LEAN_MASS, it.mass.inKilograms, it.time, it.zoneOffset)
                },
            )
            addAll(
                readIfGranted<BoneMassRecord>(granted, since) {
                    measurement(it.metadata, MeasurementType.BONE_MASS, it.mass.inKilograms, it.time, it.zoneOffset)
                },
            )
            addAll(
                readIfGranted<BodyWaterMassRecord>(granted, since) {
                    measurement(it.metadata, MeasurementType.WATER_MASS, it.mass.inKilograms, it.time, it.zoneOffset)
                },
            )
            addAll(
                readIfGranted<OxygenSaturationRecord>(granted, since) {
                    measurement(it.metadata, MeasurementType.SPO2, it.percentage.value, it.time, it.zoneOffset)
                },
            )
        }
        dao.upsertExternalMeasurements(items)
        return items.size
    }

    /** Toutes les mesures de type [T] depuis [since], page par page ; rien sans l'autorisation de lecture. */
    private suspend inline fun <reified T : Record> readIfGranted(
        granted: Set<String>,
        since: Instant,
        toEntity: (T) -> ExternalMeasurementEntity,
    ): List<ExternalMeasurementEntity> {
        if (HealthPermission.getReadPermission(T::class) !in granted) return emptyList()
        val out = ArrayList<ExternalMeasurementEntity>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
                    timeRangeFilter = TimeRangeFilter.after(since),
                    pageToken = pageToken,
                ),
            )
            response.records.forEach { out += toEntity(it) }
            pageToken = response.pageToken
        } while (pageToken != null)
        return out
    }

    private fun measurement(
        metadata: Metadata,
        type: MeasurementType,
        value: Double,
        time: Instant,
        offset: ZoneOffset?,
    ): ExternalMeasurementEntity {
        val zone: ZoneId = offset ?: ZoneId.systemDefault()
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
        private const val FIRST_SYNC_DAYS = 30L
    }
}
