package com.boucle.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.boucle.app.domain.RecoveryScore
import com.boucle.app.ui.DayUiState
import com.boucle.app.ui.components.AnimatedNumber
import com.boucle.app.ui.components.DetailHeader
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.SegmentedBar
import com.boucle.app.ui.components.StatTile
import com.boucle.app.ui.components.animatedValue
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.ChartColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Les quatre niveaux de l'échelle de récupération, dans l'ordre de l'échelle. */
private enum class RecoveryLevel(val label: String, val advice: String) {
    Low(
        "Basse",
        "Ton corps n'a pas encore bien récupéré. Garde une journée calme : marche, étirements et un coucher plus tôt.",
    ),
    Moderate(
        "Modérée",
        "Ta récupération est partielle. Une activité légère à modérée te conviendra mieux qu'une grosse séance.",
    ),
    Good(
        "Bonne",
        "Ton corps a bien récupéré cette nuit. Une séance modérée à soutenue passe bien aujourd'hui.",
    ),
    Peak(
        "Pleine forme",
        "Ton corps est pleinement reposé. C'est une bonne journée pour une séance intense si tu en as envie.",
    ),
    ;

    companion object {
        fun of(total: Int) = when {
            total >= 85 -> Peak
            total >= 67 -> Good
            total >= 34 -> Moderate
            else -> Low
        }
    }
}

/**
 * Détail de la récupération : niveau sur 100 et son échelle, les quatre composantes du score (VFC,
 * FC nocturne, respiration, sommeil), la température et le statut du système nerveux autonome.
 */
@Composable
fun RecoveryScreen(
    state: DayUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        DetailHeader("Récupération", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val score = state.recovery
            if (score == null) {
                RecoveryEmpty(Modifier.staggeredEntrance(0))
                return@Column
            }
            RecoveryHero(score, Modifier.staggeredEntrance(0))
            RecoveryComponents(score, state, Modifier.staggeredEntrance(1))
            RecoveryTiles(score, state, Modifier.staggeredEntrance(2))
            Text(
                if (score.personalBaseline) {
                    "Référence : tes 28 dernières nuits. Le score compare ta nuit à tes propres habitudes, pas à une moyenne générale."
                } else {
                    "Référence : celle du bracelet (pas encore assez de nuits enregistrées)."
                },
                style = BoucleType.small,
                color = BoucleColors.OnSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .staggeredEntrance(3),
            )
        }
    }
}

/** Bloc vert plein : score, pastille du niveau, échelle à quatre segments et conseil du jour. */
@Composable
private fun RecoveryHero(score: RecoveryScore, modifier: Modifier = Modifier) {
    val level = RecoveryLevel.of(score.total)
    ExpressiveSurface(
        color = BoucleColors.Primary,
        radii = Radii.Hero,
        contentColor = BoucleColors.OnPrimary,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Niveau de récupération", style = BoucleType.smallStrong, color = ChartColors.RingSteps)
                AnimatedNumber(score.total, BoucleType.heroXL)
            }
            Text(
                level.label,
                style = BoucleType.pillLarge,
                color = ChartColors.Vigorous,
                modifier = Modifier
                    .background(ChartColors.RingSteps, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        RecoveryScale(level)
        Text(level.advice, style = BoucleType.body)
    }
}

/**
 * Échelle Basse / Modérée / Bonne / Pleine forme. Le segment du niveau courant s'allume avec le
 * ressort Expressive : sa couleur s'éclaircit et ses coins intérieurs s'arrondissent en pilule.
 */
@Composable
private fun RecoveryScale(level: RecoveryLevel, modifier: Modifier = Modifier) {
    val levels = RecoveryLevel.entries
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    Column(
        modifier.semantics {
            contentDescription = "Échelle : ${levels.joinToString { it.label }}. Niveau actuel : ${level.label}"
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(12.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            levels.forEachIndexed { index, entry ->
                key(entry) {
                    val active = remember { Animatable(0f) }
                    LaunchedEffect(entry == level) { active.animateTo(if (entry == level) 1f else 0f, spec) }
                    val progress = active.value.coerceIn(0f, 1f)
                    val inner = lerp(4.dp, 6.dp, progress)
                    val outer = 6.dp
                    val shape = RoundedCornerShape(
                        topStart = if (index == 0) outer else inner,
                        bottomStart = if (index == 0) outer else inner,
                        topEnd = if (index == levels.lastIndex) outer else inner,
                        bottomEnd = if (index == levels.lastIndex) outer else inner,
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(lerp(ChartColors.RingTrack, ChartColors.RingSteps, progress), shape),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            levels.forEach { entry ->
                val current = entry == level
                Text(
                    entry.label,
                    style = if (current) BoucleType.caption.copy(fontWeight = FontWeight.ExtraBold) else BoucleType.caption,
                    color = if (current) BoucleColors.OnPrimary else BoucleColors.PrimaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Carte blanche des composantes du score, une ligne par mesure disponible. */
@Composable
private fun RecoveryComponents(score: RecoveryScore, state: DayUiState, modifier: Modifier = Modifier) {
    val breathsPerMinute = state.recharge?.meanRespirationIntervalMs?.takeIf { it > 0 }?.let { 60_000f / it }
    val rows = buildList<@Composable () -> Unit> {
        score.rmssdMs?.let { rmssd ->
            add {
                RecoveryComponentRow(
                    title = "VFC (RMSSD)",
                    value = rmssd.toFloat(),
                    format = { "${it.roundToInt()} ms" },
                    score = score.hrv,
                    detail = score.rmssdBaselineMs?.let { base ->
                        val position = when {
                            rmssd > base * 1.05 -> "au-dessus de ta moyenne"
                            rmssd < base * 0.95 -> "en dessous de ta moyenne"
                            else -> "dans ta moyenne"
                        }
                        "Référence $base ms · $position"
                    },
                )
            }
        }
        score.nightHeartRate?.let { bpm ->
            add {
                RecoveryComponentRow(
                    title = "FC nocturne",
                    value = bpm.toFloat(),
                    format = { "${it.roundToInt()} bpm" },
                    score = score.heartRate,
                    detail = score.heartRateBaseline?.let { base ->
                        val position = when {
                            bpm <= base - 2 -> "plus basse que d'habitude"
                            bpm >= base + 2 -> "plus haute que d'habitude"
                            else -> "comme d'habitude"
                        }
                        "Référence $base bpm · $position"
                    },
                )
            }
        }
        breathsPerMinute?.let { rate ->
            add {
                RecoveryComponentRow(
                    title = "Respiration",
                    value = rate,
                    format = { "${"%.1f".format(Locale.FRENCH, it)} /min" },
                    score = score.respiration,
                    detail = when {
                        score.respiration == null -> "Mesurée pendant le sommeil"
                        score.respiration >= 90 -> "Stable par rapport à tes nuits récentes"
                        else -> "Plus rapide que d'habitude"
                    },
                )
            }
        }
        score.sleep?.let { sleep ->
            add {
                RecoveryComponentRow(
                    title = "Sommeil",
                    value = sleep.toFloat(),
                    format = { it.roundToInt().toString() },
                    score = sleep,
                    detail = state.sleep?.totalSec
                        ?.let { "Score de la nuit · ${it / 3600} h ${"%02d".format((it % 3600) / 60)}" }
                        ?: "Score de la nuit",
                )
            }
        }
    }
    if (rows.isEmpty()) return
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.Card,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(thickness = 1.dp, color = BoucleColors.Divider)
            row()
        }
    }
}

/** Une composante : nom, valeur qui compte, barre du sous-score sur 100 et sa référence. */
@Composable
private fun RecoveryComponentRow(
    title: String,
    value: Float,
    format: (Float) -> String,
    score: Int?,
    detail: String?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = BoucleType.bodyStrong, modifier = Modifier.alignByBaseline())
            Text(format(animatedValue(value)), style = BoucleType.valueM, modifier = Modifier.alignByBaseline())
        }
        score?.let { SegmentedBar(it / 100f, BoucleColors.Primary, BoucleColors.PrimaryContainer) }
        detail?.let { Text(it, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant) }
    }
}

/** Paire de tuiles : écart de température de la nuit, puis statut SNA du Nightly Recharge. */
@Composable
private fun RecoveryTiles(score: RecoveryScore, state: DayUiState, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val deviation = state.sleep?.skinTemperatureDeviationC
        StatTile(
            label = "Température nocturne",
            value = deviation?.let { "${recoverySigned(it, 2)} °C" } ?: "—",
            detail = when {
                deviation == null -> "Pas de mesure cette nuit"
                score.temperaturePenalty > 0 -> "Pénalité : −${score.temperaturePenalty}"
                else -> "Aucune pénalité"
            },
            radii = Radii.PairStart,
            modifier = Modifier.weight(1f),
        )
        val ans = state.recharge?.ansStatus
        if (ans != null) {
            StatTile(
                label = "Statut SNA",
                value = recoverySigned(ans, 1),
                detail = "Nightly Recharge",
                radii = Radii.PairEnd,
                modifier = Modifier.weight(1f),
            )
        } else {
            StatTile(
                label = "FC de référence",
                value = score.heartRateBaseline?.let { "$it bpm" } ?: "—",
                detail = "Base de ta FC nocturne",
                radii = Radii.PairEnd,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Valeur signée à la française : « +0,12 », « −1,4 ». */
private fun recoverySigned(value: Float, decimals: Int): String {
    val digits = "%.${decimals}f".format(Locale.FRENCH, abs(value))
    return if (value < 0f && digits.any { it in '1'..'9' }) "−$digits" else "+$digits"
}

/** Pas de score : il faut une nuit mesurée par le bracelet. */
@Composable
private fun RecoveryEmpty(modifier: Modifier = Modifier) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.Card,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Pas encore de score de récupération", style = BoucleType.rowTitle)
        Text(
            "Le score se calcule à partir de la mesure de ta nuit (Nightly Recharge). Porte le bracelet pour dormir, puis synchronise-le au réveil.",
            style = BoucleType.small,
            color = BoucleColors.OnSurfaceVariant,
        )
    }
}
