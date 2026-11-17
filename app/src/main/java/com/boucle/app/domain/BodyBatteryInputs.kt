package com.boucle.app.domain

import com.boucle.app.data.db.ActivitySamplesEntity
import com.boucle.app.data.db.HrBlockEntity
import com.boucle.app.data.db.SleepEntity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Projette les données stockées (MET, FC 24/7, stress VFC, phases de sommeil) sur la grille de 5 minutes du
 * modèle. La simulation démarre [WARMUP_DAYS] jours avant le jour affiché : une ou deux nuits suffisent
 * à effacer l'effet du niveau de départ arbitraire.
 */
object BodyBatteryInputs {
    const val WARMUP_DAYS = 2L

    private const val SLOT_SECONDS = BodyBatteryModel.SLOT_MINUTES * 60L
    private const val DEFAULT_MET_INTERVAL_SEC = 30L
    /** FC 24/7 : moyennes de 5 minutes au repos, une mesure par seconde en activité intense. */
    internal const val LOW_ACTIVITY_HR_INTERVAL_SEC = 300L
    internal const val HIGH_ACTIVITY_HR_INTERVAL_SEC = 1L
    private const val DEFAULT_RESTING_HR = 60.0
    /**
     * Le bracelet n'enregistre des PPI que par moments : une mesure de stress VFC vaut aussi pour les
     * créneaux suivants sans mesure, jusqu'à 25 minutes après.
     */
    private const val STRESS_HOLD_SLOTS = 5

    private val sleepWeights = mapOf("NONREM3" to 1.0, "REM" to 0.8, "NONREM12" to 0.6)

    class Grid(
        val start: LocalDateTime,
        val slots: List<BatterySlot>,
        val dayFrom: Int,
        val dayTo: Int,
    )

    /**
     * @param qualityByWakeDate qualité de chaque nuit, indexée par sa date de réveil (yyyy-MM-dd).
     * @param now les créneaux postérieurs sont ignorés (utile pour le jour en cours).
     * @param stress stress VFC indexé par début de créneau, tel que le renvoie [HrvStress.score].
     */
    fun build(
        date: LocalDate,
        now: LocalDateTime,
        activity: List<ActivitySamplesEntity>,
        heartRate: List<HrBlockEntity>,
        sleeps: List<SleepEntity>,
        qualityByWakeDate: Map<String, Double>,
        stress: Map<LocalDateTime, Double> = emptyMap(),
    ): Grid {
        val start = date.minusDays(WARMUP_DAYS).atStartOfDay()
        // Un jour de plus après le jour affiché : une nuit commencée avant minuit doit être vue en entier,
        // sinon toute sa recharge serait comprimée dans les quelques minutes précédant minuit.
        val dayCount = WARMUP_DAYS.toInt() + 2
        val n = dayCount * BodyBatteryModel.SLOTS_PER_DAY
        val limit = slotIndex(start, now)?.coerceAtMost(n) ?: if (now.isBefore(start)) 0 else n

        val metSum = DoubleArray(n)
        val metCount = IntArray(n)
        activity.forEach { block ->
            val blockStart = runCatching { LocalDateTime.parse(block.startTime) }.getOrNull() ?: return@forEach
            val interval = (block.metIntervalSec ?: DEFAULT_MET_INTERVAL_SEC.toInt()).toLong()
            values(block.metSamples).forEachIndexed { k, value ->
                val index = slotIndex(start, blockStart.plusSeconds(k * interval)) ?: return@forEachIndexed
                if (index < limit) {
                    metSum[index] += value
                    metCount[index]++
                }
            }
        }

        val hrSum = DoubleArray(n)
        val hrCount = IntArray(n)
        heartRate.forEach { block ->
            val day = runCatching { LocalDate.parse(block.date) }.getOrNull() ?: return@forEach
            val time = runCatching { LocalTime.parse(block.startTime) }.getOrNull() ?: return@forEach
            val interval = if ("HIGH" in block.trigger) HIGH_ACTIVITY_HR_INTERVAL_SEC else LOW_ACTIVITY_HR_INTERVAL_SEC
            val blockStart = day.atTime(time)
            values(block.samples).forEachIndexed { k, value ->
                if (value <= 0.0) return@forEachIndexed
                val index = slotIndex(start, blockStart.plusSeconds(k * interval)) ?: return@forEachIndexed
                if (index < limit) {
                    hrSum[index] += value
                    hrCount[index]++
                }
            }
        }

        val sleepWeight = DoubleArray(n)
        val nightQuality = arrayOfNulls<Double>(n)
        sleeps.forEach { entity ->
            val sleepStart = entity.startTime?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }
                ?.toLocalDateTime() ?: return@forEach
            val sleepEnd = entity.endTime?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }
                ?.toLocalDateTime() ?: return@forEach
            val inBedSec = Duration.between(sleepStart, sleepEnd).seconds
            val phases = phases(entity.phases)
            val quality = qualityByWakeDate[entity.date]
            phases.forEachIndexed { p, (offsetSec, state) ->
                val weight = sleepWeights[state] ?: return@forEachIndexed
                val endSec = phases.getOrNull(p + 1)?.first ?: inBedSec
                // Répartit la phase sur les créneaux qu'elle chevauche, au prorata du temps.
                var t = offsetSec
                while (t < endSec) {
                    val moment = sleepStart.plusSeconds(t)
                    val index = slotIndex(start, moment)
                    val secondsIntoSlot = Duration.between(start, moment).seconds.mod(SLOT_SECONDS)
                    val step = minOf(endSec - t, SLOT_SECONDS - secondsIntoSlot)
                    if (index != null && index < limit) {
                        sleepWeight[index] += weight * step / SLOT_SECONDS
                        if (quality != null) nightQuality[index] = quality
                    }
                    t += step
                }
            }
        }

        val stressBySlot = arrayOfNulls<Double>(n)
        stress.forEach { (moment, value) ->
            val index = slotIndex(start, moment) ?: return@forEach
            if (index < limit) stressBySlot[index] = value
        }
        var held: Double? = null
        var heldFor = 0
        for (i in 0 until limit) {
            val own = stressBySlot[i]
            if (own != null) {
                held = own
                heldFor = 0
            } else if (held != null && ++heldFor <= STRESS_HOLD_SLOTS) {
                stressBySlot[i] = held
            }
        }

        val slots = List(n) { i ->
            BatterySlot(
                sleepWeight = sleepWeight[i],
                nightQuality = nightQuality[i],
                met = if (metCount[i] > 0) metSum[i] / metCount[i] else null,
                hr = if (hrCount[i] > 0) hrSum[i] / hrCount[i] else null,
                stress = stressBySlot[i],
            )
        }
        val dayFrom = WARMUP_DAYS.toInt() * BodyBatteryModel.SLOTS_PER_DAY
        return Grid(start, slots, dayFrom, dayFrom + BodyBatteryModel.SLOTS_PER_DAY)
    }

    /**
     * FC de repos de référence : la FC nocturne la plus récente du bracelet, à défaut le 5e centile de
     * la FC de la période, à défaut 60 bpm.
     */
    fun restingHeartRate(nightlyRriMs: Int?, heartRate: List<HrBlockEntity>): Double {
        nightlyRriMs?.takeIf { it > 0 }?.let { return 60_000.0 / it }
        val samples = heartRate.flatMap { values(it.samples) }.filter { it > 0 }.sorted()
        if (samples.isEmpty()) return DEFAULT_RESTING_HR
        return samples[(samples.size * 0.05).toInt().coerceAtMost(samples.lastIndex)]
    }

    private fun slotIndex(start: LocalDateTime, moment: LocalDateTime): Int? {
        val seconds = Duration.between(start, moment).seconds
        if (seconds < 0) return null
        val index = seconds / SLOT_SECONDS
        return if (index > Int.MAX_VALUE) null else index.toInt()
    }

    private fun values(csv: String): List<Double> =
        if (csv.isEmpty()) emptyList() else csv.split(',').mapNotNull { it.toDoubleOrNull() }

    private fun phases(csv: String): List<Pair<Long, String>> =
        if (csv.isEmpty()) emptyList() else csv.split(',').mapNotNull { item ->
            val parts = item.split(':')
            if (parts.size != 2) return@mapNotNull null
            (parts[0].toLongOrNull() ?: return@mapNotNull null) to parts[1]
        }.sortedBy { it.first }
}
