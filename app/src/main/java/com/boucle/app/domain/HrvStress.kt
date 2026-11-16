package com.boucle.app.domain

import com.boucle.app.data.db.PpiBlockEntity
import com.boucle.app.data.db.SleepEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stress diurne de 0 à 100 calculé à partir de la VFC, comme celui de Garmin : 0–25 repos, 26–50 faible,
 * 51–75 moyen, 76–100 élevé.
 *
 * Les intervalles PPI 24/7 du bracelet sont nettoyés (bornes physiologiques, estimation d'erreur du
 * capteur, battements ectopiques), puis on calcule le RMSSD de chaque créneau de 5 minutes. Son
 * logarithme est comparé à ta ligne de base des [BASELINE_DAYS] derniers jours d'éveil (Z-score), et le
 * Z-score passe dans une sigmoïde inversée : une VFC au-dessus de ta normale donne un stress bas, en
 * dessous un stress élevé.
 *
 * Seules les fenêtres d'éveil comptent (la VFC nocturne est bien plus haute et relève du Nightly
 * Recharge), et les fenêtres à l'effort n'alimentent pas la ligne de base : la VFC y reflète la charge,
 * pas l'état de récupération. Les seuils sont des valeurs de départ réglables.
 */
object HrvStress {
    const val BASELINE_DAYS = 7L

    private const val PPI_MIN_MS = 300.0
    private const val PPI_MAX_MS = 2_000.0
    /** Battement écarté si le bracelet estime son erreur au-delà de ce seuil. */
    private const val MAX_ERROR_ESTIMATE_MS = 25.0
    /** Écart relatif à la médiane glissante au-delà duquel un battement est ectopique. */
    private const val ECTOPIC_THRESHOLD = 0.20
    private const val MEDIAN_WINDOW = 5
    /** Une fenêtre de 5 min doit compter au moins ce nombre de battements bruts… */
    private const val MIN_BEATS = 30
    /** … dont au moins cette part de valides. */
    private const val MIN_VALID_RATIO = 0.5
    private const val RMSSD_FLOOR_MS = 1.0

    /** Ligne de base par défaut de ln(RMSSD) diurne (≈ 45 ms), avant d'avoir assez de mesures. */
    private const val PRIOR_MEAN = 3.8
    private const val PRIOR_STD = 0.5
    private const val MIN_STD = 0.1
    /** Nombre de fenêtres personnelles (≈ 3 h cumulées) au-delà duquel le prior n'est plus utilisé. */
    private const val COLD_START_WINDOWS = 36
    /** Une fenêtre à plus de FC de repos + cette marge est considérée comme de l'effort. */
    private const val EXERCISE_HR_MARGIN = 30.0

    private const val SIGMOID_SLOPE = 1.5
    /**
     * Décalage de la sigmoïde : un moment d'éveil ordinaire (Z = 0) donne un stress d'environ 30, dans la
     * zone « faible », plutôt que 50 qui viderait la batterie toute la journée.
     */
    private const val SIGMOID_OFFSET = 0.55

    /** Mesures d'un créneau de 5 minutes. */
    data class Window(
        val start: LocalDateTime,
        val rmssd: Double,
        val hr: Double,
        val beats: Int,
        val validRatio: Double,
    ) {
        val lnRmssd: Double get() = ln(max(rmssd, RMSSD_FLOOR_MS))
    }

    /** Ligne de base courante de ln(RMSSD) ; [weight] = part des données personnelles face au prior. */
    data class Baseline(val mean: Double, val std: Double, val weight: Double)

    /** Stress [0, 100] d'une mesure de ln(RMSSD) face à une ligne de base. */
    fun stress(lnRmssd: Double, baseline: Baseline): Double {
        val z = (lnRmssd - baseline.mean) / baseline.std
        return 100.0 / (1.0 + exp(SIGMOID_SLOPE * (z + SIGMOID_OFFSET)))
    }

    /** Plages de sommeil (heure locale) des nuits enregistrées. */
    fun sleepIntervals(sleeps: List<SleepEntity>): List<ClosedRange<LocalDateTime>> = sleeps.mapNotNull { s ->
        val start = s.startTime?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }?.toLocalDateTime()
        val end = s.endTime?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }?.toLocalDateTime()
        if (start != null && end != null && start < end) start..end else null
    }

    /**
     * Découpe les blocs PPI en créneaux de [slotMinutes] alignés sur l'heure et calcule le RMSSD de
     * chacun. Les créneaux trop pauvres ou trop bruités sont omis.
     */
    fun windows(blocks: List<PpiBlockEntity>, slotMinutes: Int = BodyBatteryModel.SLOT_MINUTES): List<Window> {
        val acc = HashMap<LocalDateTime, Accumulator>()
        val slotSeconds = slotMinutes * 60L
        blocks.forEach { block ->
            val day = runCatching { LocalDate.parse(block.date) }.getOrNull() ?: return@forEach
            val time = runCatching { LocalTime.parse(block.startTime) }.getOrNull() ?: return@forEach
            val ppi = values(block.ppiMs)
            if (ppi.isEmpty()) return@forEach
            val errors = values(block.errorEstimateMs)
            // Chaque battement est daté par la fin de son intervalle.
            var t = day.atTime(time)
            var segmentSlot: LocalDateTime? = null
            val segment = ArrayList<Pair<Double, Double?>>()
            ppi.forEachIndexed { i, value ->
                t = t.plusNanos((value.coerceAtLeast(0.0) * 1_000_000).toLong())
                val slot = floor(t, slotSeconds)
                if (slot != segmentSlot && segment.isNotEmpty()) {
                    acc.getOrPut(segmentSlot!!) { Accumulator() }.add(segment)
                    segment.clear()
                }
                segmentSlot = slot
                segment += value to errors.getOrNull(i)
            }
            if (segment.isNotEmpty()) acc.getOrPut(segmentSlot!!) { Accumulator() }.add(segment)
        }
        return acc.entries.mapNotNull { (start, a) -> a.toWindow(start) }.sortedBy { it.start }
    }

    /**
     * Stress de chaque fenêtre d'éveil, indexé par début de créneau. La ligne de base de chaque fenêtre
     * ne contient que des fenêtres antérieures (pas de fuite du futur) des [BASELINE_DAYS] derniers jours.
     */
    fun score(
        windows: List<Window>,
        asleep: List<ClosedRange<LocalDateTime>>,
        restingHr: Double,
    ): Map<LocalDateTime, Double> {
        val tracker = BaselineTracker()
        val result = LinkedHashMap<LocalDateTime, Double>()
        windows.sortedBy { it.start }.forEach { w ->
            if (asleep.any { w.start in it }) return@forEach
            result[w.start] = stress(w.lnRmssd, tracker.baseline(w.start))
            if (w.hr <= restingHr + EXERCISE_HR_MARGIN) tracker.add(w.start, w.lnRmssd)
        }
        return result
    }

    /** Moyenne et écart-type glissants de ln(RMSSD), mélangés au prior tant qu'il y a peu de mesures. */
    class BaselineTracker {
        private val samples = ArrayDeque<Pair<LocalDateTime, Double>>()
        // Sommes centrées sur le prior pour limiter les erreurs d'arrondi.
        private var sum = 0.0
        private var sumSq = 0.0

        fun add(time: LocalDateTime, lnRmssd: Double) {
            val x = lnRmssd - PRIOR_MEAN
            samples.addLast(time to x)
            sum += x
            sumSq += x * x
        }

        fun baseline(now: LocalDateTime): Baseline {
            val cutoff = now.minusDays(BASELINE_DAYS)
            while (samples.isNotEmpty() && samples.first().first < cutoff) {
                val (_, x) = samples.removeFirst()
                sum -= x
                sumSq -= x * x
            }
            val n = samples.size
            if (n < 2) return Baseline(PRIOR_MEAN, PRIOR_STD, 0.0)
            val meanC = sum / n
            val variance = max(sumSq / n - meanC * meanC, 0.0) * n / (n - 1)
            val w = min(1.0, n.toDouble() / COLD_START_WINDOWS)
            val mean = (1 - w) * PRIOR_MEAN + w * (PRIOR_MEAN + meanC)
            val std = sqrt((1 - w) * PRIOR_STD * PRIOR_STD + w * variance)
            return Baseline(mean, max(std, MIN_STD), w)
        }
    }

    /** Agrège les segments contigus de battements d'un même créneau. */
    private class Accumulator {
        var total = 0
        var valid = 0
        var sumSqDiff = 0.0
        var diffs = 0
        var sumRr = 0.0
        var kept = 0

        fun add(segment: List<Pair<Double, Double?>>) {
            val n = segment.size
            total += n
            // Étape A : bornes physiologiques et estimation d'erreur du capteur.
            val ok = BooleanArray(n) { i ->
                val (value, error) = segment[i]
                value in PPI_MIN_MS..PPI_MAX_MS && (error == null || error <= MAX_ERROR_ESTIMATE_MS)
            }
            val idx = (0 until n).filter { ok[it] }
            if (idx.size < 3) return
            // Étape B : battements ectopiques face à la médiane glissante, remplacés par interpolation.
            val kv = DoubleArray(idx.size) { segment[idx[it]].first }
            val median = medianFilter(kv, MEDIAN_WINDOW)
            val ectopic = BooleanArray(kv.size) { abs(kv[it] - median[it]) > ECTOPIC_THRESHOLD * median[it] }
            val good = kv.indices.filter { !ectopic[it] }
            valid += good.size
            if (good.size < 2) return
            val cleaned = DoubleArray(n)
            kv.indices.forEach { k -> cleaned[idx[k]] = if (ectopic[k]) interpolate(k, good, kv) else kv[k] }
            idx.forEach { i ->
                sumRr += cleaned[i]
                kept++
            }
            // Différences uniquement entre battements consécutifs conservés : un battement écarté coupe la suite.
            for (i in 1 until n) {
                if (ok[i] && ok[i - 1]) {
                    val d = cleaned[i] - cleaned[i - 1]
                    sumSqDiff += d * d
                    diffs++
                }
            }
        }

        fun toWindow(start: LocalDateTime): Window? {
            if (total < MIN_BEATS || kept == 0 || diffs < MIN_BEATS / 2) return null
            val ratio = valid.toDouble() / total
            if (ratio < MIN_VALID_RATIO) return null
            return Window(start, sqrt(sumSqDiff / diffs), 60_000.0 / (sumRr / kept), total, ratio)
        }
    }

    private fun interpolate(k: Int, good: List<Int>, values: DoubleArray): Double {
        val pos = good.binarySearch(k).let { -(it + 1) }
        return when {
            pos <= 0 -> values[good.first()]
            pos >= good.size -> values[good.last()]
            else -> {
                val a = good[pos - 1]
                val b = good[pos]
                values[a] + (values[b] - values[a]) * (k - a) / (b - a)
            }
        }
    }

    /** Médiane glissante centrée, bords répliqués (comme `scipy.ndimage.median_filter(mode="nearest")`). */
    private fun medianFilter(x: DoubleArray, size: Int): DoubleArray {
        val half = size / 2
        return DoubleArray(x.size) { i ->
            DoubleArray(size) { j -> x[(i - half + j).coerceIn(0, x.lastIndex)] }.also { it.sort() }[half]
        }
    }

    private fun floor(t: LocalDateTime, slotSeconds: Long): LocalDateTime {
        val secondOfDay = t.toLocalTime().toSecondOfDay().toLong()
        return t.toLocalDate().atStartOfDay().plusSeconds(secondOfDay - secondOfDay % slotSeconds)
    }

    private fun values(csv: String): List<Double> =
        if (csv.isEmpty()) emptyList() else csv.split(',').mapNotNull { it.trim().toDoubleOrNull() }
}
