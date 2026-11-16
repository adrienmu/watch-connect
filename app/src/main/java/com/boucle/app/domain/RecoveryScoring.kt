package com.boucle.app.domain

import com.boucle.app.data.db.NightlyRechargeEntity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Score de récupération sur 100, calculé localement à partir des mesures nocturnes du bracelet.
 *
 * Principe commun aux scores de récupération open source (OpenWhoop, PhysioDSP, Emuqu) : on compare
 * la nuit à ta propre base de référence, en écarts-types (z-score), plutôt qu'à une valeur absolue
 * qui n'a pas de sens d'une personne à l'autre. Les barèmes ci-dessous sont les nôtres.
 *
 *  - VFC (RMSSD nocturne) : 50 % — plus haut que ta base = mieux
 *  - FC nocturne : 25 % — plus basse que ta base = mieux
 *  - Respiration : 10 % — rythme plus rapide que ta base = pénalité
 *  - Sommeil : 15 % — le score de sommeil de la nuit
 *  - Température cutanée : au-delà de +0,5 °C par rapport à ta référence, jusqu'à -15 points (signal d'alerte,
 *    par exemple début de maladie)
 *
 * La base de référence est la médiane de tes 28 dernières nuits dès qu'on en a 5 ; avant cela, on
 * utilise celle que le bracelet fournit avec le Nightly Recharge, donc le score existe dès la première nuit.
 */
enum class RecoveryBand { HIGH, MODERATE, LOW }

data class RecoveryScore(
    val total: Int,
    val hrv: Int?,
    val heartRate: Int?,
    val respiration: Int?,
    val sleep: Int?,
    val temperaturePenalty: Int,
    val rmssdMs: Int?,
    val rmssdBaselineMs: Int?,
    val nightHeartRate: Int?,
    val heartRateBaseline: Int?,
    /** `false` quand la base de référence vient du bracelet faute d'assez de nuits enregistrées. */
    val personalBaseline: Boolean,
) {
    val band: RecoveryBand
        get() = when {
            total >= 67 -> RecoveryBand.HIGH
            total >= 34 -> RecoveryBand.MODERATE
            else -> RecoveryBand.LOW
        }
}

object RecoveryScoring {
    const val WEIGHT_HRV = 0.50
    const val WEIGHT_HEART_RATE = 0.25
    const val WEIGHT_RESPIRATION = 0.10
    const val WEIGHT_SLEEP = 0.15

    const val MIN_BASELINE_NIGHTS = 5
    const val BASELINE_WINDOW_NIGHTS = 28

    private const val TEMPERATURE_THRESHOLD_C = 0.5
    private const val TEMPERATURE_MAX_PENALTY = 15.0

    private class Baseline(val center: Double, val sd: Double)

    /** @param history nuits précédentes (jamais la nuit scorée), de la plus ancienne à la plus récente. */
    fun score(
        night: NightlyRechargeEntity,
        history: List<NightlyRechargeEntity>,
        sleepScore: Int?,
        skinTemperatureDeviationC: Float?,
    ): RecoveryScore? {
        val past = history.takeLast(BASELINE_WINDOW_NIGHTS)

        val rmssd = night.meanRmssdMs?.takeIf { it > 0 }?.toDouble()
        val rmssdPersonal = personal(past.mapNotNull { it.meanRmssdMs?.takeIf { v -> v > 0 }?.toDouble() }, 5.0, 0.10)
        val rmssdBase = rmssdPersonal ?: polar(night.baselineRmssdMs?.toDouble(), night.baselineRmssdSd?.toDouble(), 5.0, 0.10)
        val hrv = if (rmssd != null && rmssdBase != null) {
            clamp(70.0 + 25.0 * (rmssd - rmssdBase.center) / rmssdBase.sd)
        } else null

        val hr = night.meanRriMs?.takeIf { it > 0 }?.let { 60_000.0 / it }
        val hrPersonal = personal(past.mapNotNull { it.meanRriMs?.takeIf { v -> v > 0 }?.let { v -> 60_000.0 / v } }, 2.0, 0.0)
        val hrBase = hrPersonal ?: night.baselineRriMs?.takeIf { it > 0 }?.let { rri ->
            val center = 60_000.0 / rri
            // Conversion de l'écart-type : d(FC) ≈ d(RRI) × 60000 / RRI².
            val sd = (night.baselineRriSd ?: 0) * 60_000.0 / (rri.toDouble() * rri)
            Baseline(center, max(sd, 2.0))
        }
        val heartRate = if (hr != null && hrBase != null) {
            clamp(70.0 - 25.0 * (hr - hrBase.center) / hrBase.sd)
        } else null

        val breath = night.meanRespirationIntervalMs?.takeIf { it > 0 }?.let { 60_000.0 / it }
        val breathPersonal = personal(
            past.mapNotNull { it.meanRespirationIntervalMs?.takeIf { v -> v > 0 }?.let { v -> 60_000.0 / v } },
            0.5,
            0.0,
        )
        val breathBase = breathPersonal ?: night.baselineRespirationIntervalMs?.takeIf { it > 0 }?.let { interval ->
            val sd = (night.baselineRespirationIntervalSd ?: 0) * 60_000.0 / (interval.toDouble() * interval)
            Baseline(60_000.0 / interval, max(sd, 0.5))
        }
        val respiration = if (breath != null && breathBase != null) {
            // Seul un rythme plus rapide que d'habitude pénalise ; une demi-déviation est tolérée.
            clamp(100.0 - 30.0 * max(0.0, (breath - breathBase.center) / breathBase.sd - 0.5))
        } else null

        val parts = listOfNotNull(
            hrv?.let { WEIGHT_HRV to it },
            heartRate?.let { WEIGHT_HEART_RATE to it },
            respiration?.let { WEIGHT_RESPIRATION to it },
            sleepScore?.let { WEIGHT_SLEEP to it.toDouble() },
        )
        // Sans VFC ni FC nocturne, le score n'aurait aucun sens.
        if (hrv == null && heartRate == null) return null

        val weighted = parts.sumOf { it.first * it.second } / parts.sumOf { it.first }
        val penalty = skinTemperatureDeviationC?.let {
            min(TEMPERATURE_MAX_PENALTY, max(0.0, (it - TEMPERATURE_THRESHOLD_C) * TEMPERATURE_MAX_PENALTY))
        } ?: 0.0

        return RecoveryScore(
            total = clamp(weighted - penalty).roundToInt(),
            hrv = hrv?.roundToInt(),
            heartRate = heartRate?.roundToInt(),
            respiration = respiration?.roundToInt(),
            sleep = sleepScore,
            temperaturePenalty = penalty.roundToInt(),
            rmssdMs = rmssd?.roundToInt(),
            rmssdBaselineMs = rmssdBase?.center?.roundToInt(),
            nightHeartRate = hr?.roundToInt(),
            heartRateBaseline = hrBase?.center?.roundToInt(),
            personalBaseline = rmssdPersonal != null,
        )
    }

    /** Médiane et écart-type des nuits passées, avec un plancher pour éviter de diviser par presque zéro. */
    private fun personal(values: List<Double>, minSd: Double, minRelativeSd: Double): Baseline? {
        if (values.size < MIN_BASELINE_NIGHTS) return null
        val center = median(values)
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        return Baseline(center, max(sd, max(minSd, minRelativeSd * center)))
    }

    private fun polar(center: Double?, sd: Double?, minSd: Double, minRelativeSd: Double): Baseline? {
        if (center == null || center <= 0.0) return null
        return Baseline(center, max(sd ?: 0.0, max(minSd, minRelativeSd * center)))
    }

    private fun clamp(value: Double) = min(100.0, max(0.0, value))
}
