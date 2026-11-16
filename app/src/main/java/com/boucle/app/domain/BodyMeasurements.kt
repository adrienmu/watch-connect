package com.boucle.app.domain

import com.boucle.app.data.db.ExternalMeasurementEntity
import java.time.LocalDate

/** Types de mesures lues dans Health Connect, et leur unité de stockage. */
enum class MeasurementType(val unit: String) {
    WEIGHT("kg"),
    BODY_FAT("%"),
    LEAN_MASS("kg"),
    BONE_MASS("kg"),
    WATER_MASS("kg"),
    SPO2("%"),
    /** VFC ponctuelle de la montre (RMSSD), pour le stress en journée et la récupération sans bracelet. */
    HRV_RMSSD("ms"),
}

/** Dernière pesée à la date affichée et son évolution. */
data class BodySummary(
    val weightKg: Double,
    /** Jour de la pesée (la plus récente au plus tard ce jour-là). */
    val weighedOn: LocalDate,
    /** Écart avec la dernière pesée d'au moins 7 jours plus tôt ; `null` sans pesée assez ancienne. */
    val change7Days: Double?,
    val change30Days: Double?,
    val bodyFatPct: Double?,
    val leanMassKg: Double?,
    val boneMassKg: Double?,
    val waterMassKg: Double?,
    val source: String,
)

data class Spo2Stats(val average: Double, val min: Double, val count: Int)

object BodyMeasurements {
    /** Profondeur d'historique à charger pour [body] : évolution sur 30 jours, même avec des pesées espacées. */
    const val HISTORY_DAYS = 60L
    /** Une mesure de composition n'est montrée que si elle date au plus de ce nombre de jours avant la pesée. */
    private const val COMPOSITION_MAX_AGE_DAYS = 7L

    /** Poids et composition au [date] ; `null` sans aucune pesée jusqu'à ce jour. */
    fun body(measurements: List<ExternalMeasurementEntity>, date: LocalDate): BodySummary? {
        val key = date.toString()
        val upTo = measurements.filter { it.date <= key }.sortedBy { it.time }
        val weights = upTo.filter { it.type == MeasurementType.WEIGHT.name }
        val latest = weights.lastOrNull() ?: return null
        val weighedOn = LocalDate.parse(latest.date)

        fun changeOver(days: Long): Double? {
            val limit = weighedOn.minusDays(days).toString()
            return weights.lastOrNull { it.date <= limit }?.let { latest.value - it.value }
        }

        fun composition(type: MeasurementType): Double? {
            val oldest = weighedOn.minusDays(COMPOSITION_MAX_AGE_DAYS).toString()
            return upTo.lastOrNull { it.type == type.name && it.date >= oldest }?.value
        }

        return BodySummary(
            weightKg = latest.value,
            weighedOn = weighedOn,
            change7Days = changeOver(7),
            change30Days = changeOver(30),
            bodyFatPct = composition(MeasurementType.BODY_FAT),
            leanMassKg = composition(MeasurementType.LEAN_MASS),
            boneMassKg = composition(MeasurementType.BONE_MASS),
            waterMassKg = composition(MeasurementType.WATER_MASS),
            source = latest.source,
        )
    }

    /** Saturation en oxygène mesurée le [date] ; `null` sans mesure ce jour-là. */
    fun spo2(measurements: List<ExternalMeasurementEntity>, date: LocalDate): Spo2Stats? {
        val values = measurements
            .filter { it.type == MeasurementType.SPO2.name && it.date == date.toString() && it.value in 50.0..100.0 }
            .map { it.value }
        if (values.isEmpty()) return null
        return Spo2Stats(values.average(), values.min(), values.size)
    }
}
