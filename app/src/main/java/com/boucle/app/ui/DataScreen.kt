package com.boucle.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.boucle.app.data.db.DailyActivityEntity
import com.boucle.app.data.db.NightlyRechargeEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.domain.BatteryBand
import com.boucle.app.domain.BatteryPoint
import com.boucle.app.domain.BodyBatteryDay
import com.boucle.app.domain.RecoveryBand
import com.boucle.app.domain.RecoveryScore
import com.boucle.app.domain.SleepBand
import com.boucle.app.domain.SleepScore
import com.boucle.app.domain.HrMaxSource
import com.boucle.app.domain.Vo2MaxEstimate
import com.boucle.app.domain.Vo2Method
import com.boucle.app.domain.band
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun DataScreen(
    state: DayUiState,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPreviousDay) { Text("‹ Veille") }
            Text(
                state.date.format(dayFormat),
                Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onNextDay, enabled = state.date < LocalDate.now()) { Text("Lendemain ›") }
        }

        if (state.isEmpty) {
            Text("Aucune donnée pour ce jour. Lance une synchronisation depuis l'onglet Bracelet.")
        }

        state.sleepScore?.let { SleepScoreCard(it) }
        state.recovery?.let { RecoveryScoreCard(it) }
        state.battery?.let { BodyBatteryCard(it, isToday = state.date == LocalDate.now()) }
        state.vo2Max?.let { Vo2MaxCard(it) }
        state.activity?.let { ActivityCard(it, state.minuteStepBlocks) }
        state.heartRate?.let {
            DataCard("Fréquence cardiaque") {
                DataRow("Min / moyenne / max", "${it.min} / ${it.average} / ${it.max} bpm")
                DataRow("Mesures", it.sampleCount.toString())
                DataRow("Intervalles PPI (VFC)", state.ppiIntervalCount.toString())
            }
        }
        state.sleep?.let { SleepCard(it) }
        state.recharge?.let { RechargeCard(it) }
        state.skinTemperature?.let {
            DataCard("Température cutanée") {
                DataRow("Min / moyenne / max", "%.1f / %.1f / %.1f °C".format(it.min, it.average, it.max))
                DataRow("Mesures", it.sampleCount.toString())
            }
        }
        state.sessions.forEach { SessionCard(it) }
    }
}

@Composable
private fun DataCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DataRow(label: String, value: String?) {
    if (value == null) return
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

private fun duration(seconds: Long?): String? {
    if (seconds == null) return null
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "$hours h %02d".format(minutes) else "$minutes min"
}

private fun duration(seconds: Int?): String? = duration(seconds?.toLong())

@Composable
private fun ActivityCard(activity: DailyActivityEntity, minuteStepBlocks: Int) {
    DataCard("Activité") {
        DataRow("Pas", activity.steps?.toString())
        DataRow("Distance", activity.distanceMeters?.let { "%.2f km".format(it / 1000f) })
        DataRow("Calories actives", activity.activityCalories?.let { "$it kcal" })
        DataRow("Calories d'entraînement", activity.trainingCalories?.let { "$it kcal" })
        DataRow("Métabolisme de base", activity.bmrCalories?.let { "$it kcal" })
        val goal = activity.activityGoal
        val achieved = activity.achievedActivity
        if (goal != null && achieved != null && goal > 0f) {
            DataRow("Objectif d'activité", "%.0f %%".format(achieved / goal * 100f))
        }
        DataRow("Sédentaire", duration(activity.sedentarySec))
        DataRow("Activité légère", duration(activity.lightSec))
        DataRow("Activité modérée", duration(activity.moderateSec))
        DataRow("Activité intense", duration(activity.vigorousSec))
        DataRow("Bracelet non porté", duration(activity.nonWearSec))
        DataRow("Blocs de pas à la minute", minuteStepBlocks.toString())
    }
}

@Composable
private fun SleepCard(sleep: SleepStats) {
    DataCard("Sommeil") {
        if (sleep.start != null && sleep.end != null) {
            DataRow("Coucher → réveil", "${sleep.start.format(timeFormat)} → ${sleep.end.format(timeFormat)}")
        }
        DataRow("Durée au lit", duration(sleep.totalSec))
        DataRow("Objectif", sleep.goalMinutes?.let { duration(it * 60) })
        DataRow("Sommeil profond", duration(sleep.secondsByState["NONREM3"]))
        DataRow("Sommeil léger", duration(sleep.secondsByState["NONREM12"]))
        DataRow("Sommeil paradoxal", duration(sleep.secondsByState["REM"]))
        DataRow("Éveils", duration(sleep.secondsByState["WAKE"]))
        DataRow("Cycles", sleep.cycleCount.takeIf { it > 0 }?.toString())
        DataRow("Température nocturne", sleep.skinTemperatureC?.let { "%.1f °C".format(it) })
        DataRow("Écart à ta référence", sleep.skinTemperatureDeviationC?.let { "%+.2f °C".format(it) })
    }
}

@Composable
private fun RechargeCard(recharge: NightlyRechargeEntity) {
    DataCard("Nightly Recharge") {
        DataRow("Statut SNA", recharge.ansStatus?.let { "%+.1f".format(it) })
        DataRow("Niveau de récupération", recharge.recoveryIndicator?.toString())
        DataRow("VFC (RMSSD)", recharge.meanRmssdMs?.let { "$it ms" })
        DataRow("VFC de référence", recharge.baselineRmssdMs?.let { "$it ms" })
        DataRow("FC nocturne", recharge.meanRriMs?.takeIf { it > 0 }?.let { "${60_000 / it} bpm" })
        DataRow(
            "Fréquence respiratoire",
            recharge.meanRespirationIntervalMs?.takeIf { it > 0 }?.let { "%.1f /min".format(60_000f / it) },
        )
    }
}

@Composable
private fun SessionCard(session: TrainingSessionEntity) {
    DataCard(session.name?.takeIf { it.isNotBlank() } ?: "Entraînement") {
        DataRow("Début", session.startTime?.substringAfter('T')?.take(5))
        DataRow("Durée", duration(session.durationSec))
        DataRow("Sport (identifiant Polar)", session.sportId?.toString())
        DataRow("Calories", session.calories?.let { "$it kcal" })
        DataRow("Distance", session.distanceMeters?.takeIf { it > 0f }?.let { "%.2f km".format(it / 1000f) })
        DataRow("FC moyenne / max", session.averageHr?.let { "$it / ${session.maxHr ?: "–"} bpm" })
        DataRow("Charge cardio", session.cardioLoad?.let { "%.0f".format(it) })
    }
}

@Composable
private fun ScoreCard(
    title: String,
    total: Int,
    label: String,
    components: List<Pair<String, Int?>>,
    footnote: String? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(total.toString(), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
                Text(label, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
            }
            components.forEach { (name, value) ->
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        Text(name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value?.toString() ?: "–")
                    }
                    LinearProgressIndicator(
                        progress = { (value ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            footnote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SleepScoreCard(score: SleepScore) {
    val label = when (score.band) {
        SleepBand.EXCELLENT -> "Excellent"
        SleepBand.GOOD -> "Bon"
        SleepBand.FAIR -> "Moyen"
        SleepBand.POOR -> "Faible"
    }
    ScoreCard(
        title = "Score de sommeil",
        total = score.total,
        label = label,
        components = listOf(
            "Durée" to score.duration,
            "Stades (profond, paradoxal)" to score.stages,
            "Régularité" to score.consistency,
            "Continuité" to score.interruptions,
        ),
        footnote = if (score.consistency == null) {
            "Régularité indisponible : il faut au moins 3 nuits précédentes."
        } else null,
    )
}

@Composable
private fun RecoveryScoreCard(score: RecoveryScore) {
    val label = when (score.band) {
        RecoveryBand.HIGH -> "Élevée"
        RecoveryBand.MODERATE -> "Modérée"
        RecoveryBand.LOW -> "Faible"
    }
    val details = buildList {
        if (score.rmssdMs != null && score.rmssdBaselineMs != null) {
            add("VFC ${score.rmssdMs} ms (référence ${score.rmssdBaselineMs} ms)")
        }
        if (score.nightHeartRate != null && score.heartRateBaseline != null) {
            add("FC nocturne ${score.nightHeartRate} bpm (référence ${score.heartRateBaseline} bpm)")
        }
        if (score.temperaturePenalty > 0) add("Température cutanée élevée : -${score.temperaturePenalty}")
        add(
            if (score.personalBaseline) "Référence : tes 28 dernières nuits."
            else "Référence : celle du bracelet (pas encore assez de nuits enregistrées).",
        )
    }
    ScoreCard(
        title = "Récupération",
        total = score.total,
        label = label,
        components = listOf(
            "VFC" to score.hrv,
            "FC nocturne" to score.heartRate,
            "Respiration" to score.respiration,
            "Sommeil" to score.sleep,
        ),
        footnote = details.joinToString("\n"),
    )
}

@Composable
private fun BodyBatteryCard(battery: BodyBatteryDay, isToday: Boolean) {
    val label = when (battery.band) {
        BatteryBand.FULL -> "Pleine forme"
        BatteryBand.GOOD -> "Bonne"
        BatteryBand.MEDIUM -> "Moyenne"
        BatteryBand.LOW -> "Basse"
    }
    val endTime = battery.points.last().time.format(timeFormat)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Body Battery", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(battery.current.toString(), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (isToday) "$label · à $endTime" else "$label · fin de journée",
                    Modifier.padding(bottom = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            BatteryChart(battery.points)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0 h", "6 h", "12 h", "18 h", "24 h").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DataRow("Au réveil", battery.wake?.toString())
            DataRow("Plus haut / plus bas", "${battery.high} / ${battery.low}")
            DataRow("Rechargé", "+${battery.charged}")
            DataRow("Dépensé", "-${battery.drained}")
            Text(
                "Modèle maison : le sommeil recharge selon sa qualité, l'activité et le stress (FC élevée au repos) " +
                    "consomment. Ce n'est pas l'algorithme de Garmin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BatteryChart(points: List<BatteryPoint>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val width = size.width
        val height = size.height
        listOf(0f, 50f, 100f).forEach { level ->
            val y = height * (1f - level / 100f)
            drawLine(gridColor, Offset(0f, y), Offset(width, y), strokeWidth = 1.dp.toPx())
        }
        if (points.size < 2) return@Canvas
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = width * point.time.toLocalTime().toSecondOfDay() / 86_400f
            val y = height * (1f - point.level.toFloat() / 100f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun Vo2MaxCard(estimate: Vo2MaxEstimate) {
    val method = when (estimate.method) {
        Vo2Method.RUNNING -> "D'après ${estimate.runCount} course${if (estimate.runCount > 1) "s" else ""} (6 dernières semaines)"
        Vo2Method.RESTING_HR -> "D'après ta FC de repos et ta FC max"
    }
    val hrMaxSource = when (estimate.hrMaxSource) {
        HrMaxSource.PROFILE -> "profil"
        HrMaxSource.OBSERVED -> "observée"
        HrMaxSource.AGE_FORMULA -> "estimée par l'âge"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("VO2max", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(estimate.value.toString(), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
                Text("ml/kg/min", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
            }
            Text(method, style = MaterialTheme.typography.bodyMedium)
            DataRow("Estimation par la course", estimate.runningEstimate?.toString())
            DataRow("Estimation par la FC de repos", estimate.restingEstimate?.toString())
            DataRow("FC max ($hrMaxSource)", "${estimate.hrMax} bpm")
            DataRow("FC de repos (nocturne)", "${estimate.restingHr} bpm")
            Text(
                "Estimation indicative : la distance du Loop vient de l'accéléromètre (pas de GPS). " +
                    "Une FC max mesurée dans ton profil améliore nettement la précision.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
