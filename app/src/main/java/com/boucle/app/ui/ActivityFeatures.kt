package com.boucle.app.ui

import com.boucle.app.data.db.ActivityEditEntity
import com.boucle.app.data.db.ActivitySamplesEntity
import com.boucle.app.data.db.HrBlockEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.domain.ActivityDetection
import com.boucle.app.domain.BodyBatteryInputs
import com.boucle.app.domain.DetectedActivity
import com.boucle.app.domain.RoutePoint
import com.boucle.app.domain.Sport
import com.boucle.app.domain.Terrain
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * Activités lancées, reconnues et corrigées : états d'écran et règles communes (corrections appliquées aux séances,
 * nom par défaut, activités détectées dans les pas du bracelet).
 */

/** Chemin d'une séance enregistrée avec le GPS du téléphone. */
const val PHONE_SESSION_PREFIX = "phone:"

/** Une séance de la liste « Activités récentes », corrections appliquées. */
data class SessionItem(
    val session: TrainingSessionEntity,
    val sport: Sport?,
    val terrain: Terrain?,
    val detected: Boolean,
)

/** Haut de l'onglet Forme : dernières séances et activité détectée à vérifier. */
data class ActivityHomeUiState(
    val recent: List<SessionItem> = emptyList(),
    val detected: DetectedActivity? = null,
    val detectionEnabled: Boolean = true,
)

/** Séance en cours de modification. */
data class ActivityEditUiState(
    val path: String? = null,
    /** Séance d'origine, corrections déjà appliquées. */
    val session: TrainingSessionEntity? = null,
    val edit: ActivityEditEntity? = null,
    val sport: Sport? = null,
    /** Durée (s) du tracé GPS complet, `null` sans tracé : le rognage n'est proposé qu'avec un tracé. */
    val routeDurationSec: Int? = null,
    /** Altitude le long du tracé complet (sous-échantillonnée), pour situer le rognage. */
    val profile: List<Float> = emptyList(),
)

/** Corrections saisies dans l'écran « Modifier l'activité ». */
data class ActivityEditForm(
    val name: String,
    val sport: Sport?,
    val terrain: Terrain?,
    val trimStartSec: Int,
    val trimEndSec: Int,
    val notes: String,
)

/** Séances visibles : les supprimées sont retirées, les corrections (nom, sport, rognage) appliquées. */
fun applyEdits(sessions: List<TrainingSessionEntity>, edits: Map<String, ActivityEditEntity>): List<TrainingSessionEntity> =
    sessions.mapNotNull { session ->
        val edit = edits[session.path] ?: return@mapNotNull session
        if (edit.deleted) null else session.withEdit(edit)
    }

fun TrainingSessionEntity.withEdit(edit: ActivityEditEntity?): TrainingSessionEntity {
    edit ?: return this
    // Un début rogné décale l'heure de départ d'autant.
    val shiftedStart = edit.trimStartSec?.takeIf { it > 0 }?.let { trim ->
        startTime?.let { runCatching { LocalDateTime.parse(it).plusSeconds(trim.toLong()).toString() }.getOrNull() }
    }
    return copy(
        startTime = shiftedStart ?: startTime,
        name = edit.name?.takeIf { it.isNotBlank() } ?: name,
        sportId = Sport.named(edit.sport)?.sportId ?: sportId,
        durationSec = edit.trimmedDurationSec ?: durationSec,
        distanceMeters = edit.trimmedDistanceMeters ?: distanceMeters,
    )
}

/** Points gardés après rognage de [startSec] secondes au début et [endSec] à la fin. */
fun trimRoute(points: List<RoutePoint>, startSec: Int?, endSec: Int?): List<RoutePoint> {
    if (points.isEmpty() || ((startSec ?: 0) <= 0 && (endSec ?: 0) <= 0)) return points
    val sorted = points.sortedBy { it.timeMs }
    val from = sorted.first().timeMs + (startSec ?: 0) * 1000L
    val to = sorted.last().timeMs - (endSec ?: 0) * 1000L
    return sorted.filter { it.timeMs in from..to }
}

/** « Course du matin », « Sortie vélo du soir », « Marche de l'après-midi ». */
fun defaultSessionName(sport: Sport, time: LocalTime): String {
    val moment = when (time.hour) {
        in 5..10 -> "du matin"
        in 11..13 -> "du midi"
        in 14..17 -> "de l'après-midi"
        in 18..22 -> "du soir"
        else -> "de nuit"
    }
    val what = when (sport) {
        Sport.WALK -> "Marche"
        Sport.RUN -> "Course"
        Sport.BIKE -> "Sortie vélo"
    }
    return "$what $moment"
}

/** Activités repérées dans les pas à la minute de [date], hors séances existantes et propositions déjà traitées. */
fun detectActivities(
    date: LocalDate,
    samples: List<ActivitySamplesEntity>,
    sessions: List<TrainingSessionEntity>,
    handled: Set<String>,
): List<DetectedActivity> {
    val blocks = samples.mapNotNull { block ->
        val start = runCatching { LocalDateTime.parse(block.startTime) }.getOrNull() ?: return@mapNotNull null
        val steps = if (block.stepSamples.isEmpty()) emptyList() else block.stepSamples.split(',').map { it.toIntOrNull() ?: 0 }
        Triple(start, block.stepIntervalSec ?: 60, steps)
    }
    val busy = sessions.mapNotNull { session ->
        val start = session.startTime?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return@mapNotNull null
        start to start.plusSeconds((session.durationSec ?: 0).toLong())
    }
    return ActivityDetection.detect(date, blocks, busy).filter { it.path !in handled }
}

/** FC moyenne et maximale relevées par le bracelet entre [start] et [end], le même jour. */
fun heartRateBetween(blocks: List<HrBlockEntity>, start: LocalTime, end: LocalTime): Pair<Int, Int>? {
    val values = ArrayList<Int>()
    blocks.forEach { block ->
        val blockStart = runCatching { LocalTime.parse(block.startTime) }.getOrNull() ?: return@forEach
        val interval = if ("HIGH" in block.trigger) {
            BodyBatteryInputs.HIGH_ACTIVITY_HR_INTERVAL_SEC
        } else {
            BodyBatteryInputs.LOW_ACTIVITY_HR_INTERVAL_SEC
        }
        if (block.samples.isEmpty()) return@forEach
        block.samples.split(',').forEachIndexed { k, raw ->
            val bpm = raw.toIntOrNull()?.takeIf { it > 0 } ?: return@forEachIndexed
            val second = blockStart.toSecondOfDay() + k * interval
            if (second >= start.toSecondOfDay() && second <= end.toSecondOfDay()) values += bpm
        }
    }
    if (values.isEmpty()) return null
    return values.average().toInt() to values.max()
}
