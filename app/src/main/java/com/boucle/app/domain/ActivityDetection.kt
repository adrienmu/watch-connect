package com.boucle.app.domain

import java.time.LocalDate
import java.time.LocalDateTime

/*
 * Reconnaissance d'une marche ou d'une course dans les pas à la minute du bracelet : un effort continu d'au moins
 * dix minutes, hors des séances déjà enregistrées. Le vélo ne se voit pas dans les pas : il n'est pas détecté, mais
 * l'utilisateur peut corriger le sport proposé. Pur Kotlin, testable sur la JVM.
 */

/** Activité repérée, à confirmer par l'utilisateur. */
data class DetectedActivity(
    val start: LocalDateTime,
    val durationSec: Int,
    val steps: Int,
    /** Cadence moyenne (pas par minute) pendant l'effort. */
    val cadence: Int,
    val sport: Sport,
    /** Pas de chaque minute, de [MARGIN_MIN] minutes avant à [MARGIN_MIN] minutes après, pour le graphique. */
    val minuteSteps: List<Int>,
) {
    /** Identifiant stable : sert de chemin à la séance créée, ou à retenir que la proposition a été écartée. */
    val path: String get() = "$PATH_PREFIX$start"

    companion object {
        const val PATH_PREFIX = "detected:"
        const val MARGIN_MIN = 5
    }
}

object ActivityDetection {
    /** Durée minimale d'un effort proposé. */
    const val MIN_MINUTES = 10
    /** En dessous, la minute ne compte pas comme de la marche soutenue. */
    const val WALK_CADENCE = 80
    /** À partir de cette cadence médiane, c'est une course. */
    const val RUN_CADENCE = 140
    /** Minutes creuses tolérées dans un effort (feu rouge, lacet). */
    const val MAX_GAP_MINUTES = 2

    /**
     * Efforts du jour [date]. [blocks] : pour chaque bloc de pas, son instant de début, l'intervalle des mesures (s)
     * et les pas de chaque intervalle. [busy] : plages des séances existantes, qui ne sont pas proposées à nouveau.
     */
    fun detect(
        date: LocalDate,
        blocks: List<Triple<LocalDateTime, Int, List<Int>>>,
        busy: List<Pair<LocalDateTime, LocalDateTime>>,
    ): List<DetectedActivity> {
        val minutes = IntArray(MINUTES_PER_DAY)
        blocks.forEach { (start, interval, samples) ->
            if (interval <= 0) return@forEach
            samples.forEachIndexed { i, steps ->
                val at = start.plusSeconds(i.toLong() * interval)
                if (at.toLocalDate() == date) minutes[at.hour * 60 + at.minute] += steps
            }
        }
        val out = ArrayList<DetectedActivity>()
        var i = 0
        while (i < MINUTES_PER_DAY) {
            if (minutes[i] < WALK_CADENCE) {
                i++
                continue
            }
            var end = i
            var gap = 0
            var j = i + 1
            while (j < MINUTES_PER_DAY && gap <= MAX_GAP_MINUTES) {
                if (minutes[j] >= WALK_CADENCE) {
                    end = j
                    gap = 0
                } else {
                    gap++
                }
                j++
            }
            val length = end - i + 1
            if (length >= MIN_MINUTES) {
                val start = date.atStartOfDay().plusMinutes(i.toLong())
                val finish = start.plusMinutes(length.toLong())
                if (busy.none { (from, to) -> from < finish && start < to }) {
                    val active = (i..end).map { minutes[it] }.filter { it >= WALK_CADENCE }.sorted()
                    val steps = (i..end).sumOf { minutes[it] }
                    out += DetectedActivity(
                        start = start,
                        durationSec = length * 60,
                        steps = steps,
                        cadence = steps / length,
                        sport = if (active[active.size / 2] >= RUN_CADENCE) Sport.RUN else Sport.WALK,
                        minuteSteps = ((i - DetectedActivity.MARGIN_MIN)..(end + DetectedActivity.MARGIN_MIN))
                            .map { minutes.getOrElse(it) { 0 } },
                    )
                }
            }
            i = end + 1
        }
        return out
    }

    /** Distance estimée d'après les pas : longueur de pas proportionnelle à la taille, plus longue en courant. */
    fun distanceMeters(steps: Int, sport: Sport, heightCm: Float?): Double {
        val height = heightCm?.takeIf { it > 0f }?.toDouble() ?: DEFAULT_HEIGHT_CM
        val stride = height / 100 * if (sport == Sport.RUN) 0.65 else 0.414
        return steps * stride
    }

    private const val MINUTES_PER_DAY = 1440
    private const val DEFAULT_HEIGHT_CM = 170.0
}
