package com.boucle.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boucle.app.domain.ChronicStress
import com.boucle.app.domain.StressZone
import com.boucle.app.domain.Wellbeing
import com.boucle.app.domain.WellbeingDay
import com.boucle.app.domain.WellbeingLevel
import com.boucle.app.domain.WellbeingScore
import com.boucle.app.ui.DayUiState
import com.boucle.app.ui.components.AnimatedNumber
import com.boucle.app.ui.components.DetailHeader
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.LegendDot
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.SegmentedBar
import com.boucle.app.ui.components.SharedKeys
import com.boucle.app.ui.components.sharedContainer
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.ChartColors
import com.boucle.app.ui.theme.MetricPalette
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val WellbeingLevel.advice: String
    get() = when (this) {
        WellbeingLevel.SERENE ->
            "Peu de stress en journée et des nuits réparatrices : ton système nerveux récupère très bien."
        WellbeingLevel.BALANCED ->
            "Ton stress monte par moments, puis il redescend : ton corps récupère bien."
        WellbeingLevel.FRAGILE ->
            "Ton stress redescend moins bien cette semaine. Prévois de vraies pauses et des nuits plus longues."
        WellbeingLevel.WATCH ->
            "Ton corps est resté en alerte une bonne partie de la semaine. Lève le pied dès que tu peux."
    }

private val TREND_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
private val BODY_TEXT = BoucleColors.OnSurface.copy(alpha = 0.9f)

/**
 * Détail du bien-être : score sur 7 jours et son échelle, stress de la journée, tendance sur
 * 4 semaines, composantes du score, puis l'explication (VFC, stress chronique et inflammation,
 * surcharge mentale, limites de la mesure).
 */
@Composable
fun WellbeingScreen(
    state: DayUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        DetailHeader("Bien-être", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val wellbeing = state.wellbeing
            if (wellbeing == null) {
                WellbeingEmpty(Modifier.sharedContainer(SharedKeys.WELLBEING, Radii.Card))
                WellbeingExplainer(Modifier.staggeredEntrance(1))
                return@Column
            }
            WellbeingHero(wellbeing.score, Modifier.sharedContainer(SharedKeys.WELLBEING, Radii.Hero))
            StressDayCard(wellbeing, state.date == LocalDate.now(), Modifier.staggeredEntrance(1))
            StressTrendCard(wellbeing, state.date, Modifier.staggeredEntrance(2))
            wellbeing.score?.let { WellbeingComponents(it, Modifier.staggeredEntrance(3)) }
            WellbeingExplainer(Modifier.staggeredEntrance(4))
            Text(
                "Calculé sur ton téléphone à partir de la VFC mesurée par ton bracelet, comparée à tes propres habitudes.",
                style = BoucleType.small,
                color = BoucleColors.OnSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .staggeredEntrance(5),
            )
        }
    }
}

/** Bloc prune clair, comme la tuile d'Aujourd'hui : score, pastille du palier, échelle et lecture de la semaine. */
@Composable
private fun WellbeingHero(score: WellbeingScore?, modifier: Modifier = Modifier) {
    val palette = MetricPalette.Wellbeing
    ExpressiveSurface(
        color = palette.container,
        radii = Radii.Hero,
        contentColor = palette.onContainer,
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
                Text("Score de bien-être · ${Wellbeing.SCORE_DAYS} jours", style = BoucleType.smallStrong)
                if (score != null) AnimatedNumber(score.total, BoucleType.heroXL) else Text("—", style = BoucleType.heroXL)
            }
            score?.let {
                Text(
                    it.level.label,
                    style = BoucleType.pillLarge,
                    modifier = Modifier
                        .background(BoucleColors.Surface, RoundedCornerShape(percent = 50))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
        if (score != null) {
            WellbeingScale(score.level)
            Text(score.level.advice, style = BoucleType.body)
        } else {
            Text(
                "Encore quelques jours de mesures : il en faut au moins ${Wellbeing.MIN_SCORE_DAYS} dans la semaine pour calculer ton score.",
                style = BoucleType.body,
            )
        }
    }
}

/** Échelle À surveiller / Fragile / Équilibré / Serein : le palier courant est plein, les autres blancs. */
@Composable
private fun WellbeingScale(level: WellbeingLevel, modifier: Modifier = Modifier) {
    val levels = WellbeingLevel.entries
    Column(
        modifier.clearAndSetSemantics {
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
                val current = entry == level
                val inner = if (current) 6.dp else 4.dp
                val shape = RoundedCornerShape(
                    topStart = if (index == 0) 6.dp else inner,
                    bottomStart = if (index == 0) 6.dp else inner,
                    topEnd = if (index == levels.lastIndex) 6.dp else inner,
                    bottomEnd = if (index == levels.lastIndex) 6.dp else inner,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(12.dp)
                        .background(if (current) MetricPalette.Wellbeing.accent else BoucleColors.Surface, shape),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            levels.forEach { entry ->
                Text(
                    entry.label,
                    style = if (entry == level) BoucleType.caption.copy(fontWeight = FontWeight.ExtraBold) else BoucleType.caption,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Carte « Stress aujourd'hui » : moyenne du jour, barres par demi-heure de 6 h à 22 h, légende des zones. */
@Composable
private fun StressDayCard(wellbeing: WellbeingDay, isToday: Boolean, modifier: Modifier = Modifier) {
    val today = wellbeing.today
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (isToday) "Stress aujourd'hui" else "Stress ce jour-là",
                style = BoucleType.sectionTitle,
                modifier = Modifier.alignByBaseline(),
            )
            today?.let {
                Text(
                    "${it.mean.roundToInt()} · ${it.zone.label}",
                    style = BoucleType.bodyStrong.copy(fontWeight = FontWeight.Bold),
                    color = MetricPalette.Wellbeing.accent,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        if (wellbeing.timeline.all { it == null }) {
            Text(
                "Pas de mesure de VFC entre ${Wellbeing.TIMELINE_START_HOUR} h et ${Wellbeing.TIMELINE_END_HOUR} h ce jour-là.",
                style = BoucleType.small,
                color = BoucleColors.OnSurfaceVariant,
            )
            return@ExpressiveSurface
        }
        StressBars(
            values = wellbeing.timeline,
            height = 110.dp,
            description = "Stress par demi-heure de ${Wellbeing.TIMELINE_START_HOUR} h à ${Wellbeing.TIMELINE_END_HOUR} h" +
                (today?.let { " : moyenne ${it.mean.roundToInt()}, ${it.zone.label.lowercase(Locale.FRENCH)}" } ?: ""),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("6 h", "10 h", "14 h", "18 h", "22 h").forEach {
                Text(it, style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant)
            }
        }
        StressLegend()
    }
}

/** Légende des quatre zones de stress, sur une ou deux lignes selon la largeur. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StressLegend(modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LegendDot("Repos 0–25", ChartColors.StressRest)
        LegendDot("Faible 26–50", ChartColors.StressLow)
        LegendDot("Moyen 51–75", ChartColors.StressMedium)
        LegendDot("Élevé 76–100", ChartColors.StressHigh)
    }
}

/**
 * Barres de stress sur une échelle de 0 à 100, colorées par zone ; une valeur absente laisse un tiret
 * gris. [band] dessine derrière les barres la zone habituelle.
 */
@Composable
private fun StressBars(
    values: List<Double?>,
    height: Dp,
    description: String,
    modifier: Modifier = Modifier,
    band: ClosedFloatingPointRange<Double>? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        band?.let {
            val bottom = height * (it.start.coerceIn(0.0, 100.0) / 100.0).toFloat()
            val span = height * ((it.endInclusive - it.start).coerceIn(0.0, 100.0) / 100.0).toFloat()
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(bottom = bottom)
                    .height(span.coerceAtLeast(6.dp))
                    .background(BoucleColors.Background, RoundedCornerShape(6.dp)),
            )
        }
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            values.forEach { value ->
                val barHeight = value?.let { (height * (it.coerceIn(0.0, 100.0) / 100.0).toFloat()).coerceAtLeast(8.dp) } ?: 4.dp
                Box(
                    Modifier
                        .weight(1f)
                        .height(barHeight)
                        .background(
                            value?.let { StressZone.of(it).color } ?: BoucleColors.Track,
                            RoundedCornerShape(4.dp),
                        ),
                )
            }
        }
    }
}

/** Carte « Sur 4 semaines » : stress moyen de chaque jour, zone habituelle et lecture du stress chronique. */
@Composable
private fun StressTrendCard(wellbeing: WellbeingDay, date: LocalDate, modifier: Modifier = Modifier) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Sur 4 semaines", style = BoucleType.sectionTitle, modifier = Modifier.alignByBaseline())
            Text(
                "stress moyen par jour",
                style = BoucleType.small,
                color = BoucleColors.OnSurfaceVariant,
                modifier = Modifier.alignByBaseline(),
            )
        }
        StressBars(
            values = wellbeing.month.map { it?.mean },
            height = 96.dp,
            description = "Stress moyen de chacun des ${Wellbeing.TREND_DAYS} derniers jours : " +
                "${wellbeing.elevatedDays} de stress moyen ou élevé sur ${wellbeing.measuredDays} mesurés",
            band = wellbeing.usualBand,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(27L, 21L, 14L, 7L, 0L).forEach { ago ->
                Text(date.minusDays(ago).format(TREND_DATE), style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant)
            }
        }
        if (wellbeing.usualBand != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val swatch = RoundedCornerShape(3.dp)
                Box(
                    Modifier
                        .size(width = 18.dp, height = 10.dp)
                        .background(BoucleColors.Background, swatch)
                        .border(1.dp, ChartColors.Sedentary, swatch),
                )
                Text("Ta zone habituelle", style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant)
            }
        }
        ChronicVerdict(wellbeing)
    }
}

/** Encart prune : le stress des 4 semaines est-il passager ou installé ? */
@Composable
private fun ChronicVerdict(wellbeing: WellbeingDay) {
    val days = wellbeing.elevatedDays
    val counted = when (days) {
        0 -> "Aucun jour de stress moyen ou élevé"
        1 -> "1 jour de stress moyen ou élevé"
        else -> "$days jours de stress moyen ou élevé"
    } + " sur ${Wellbeing.TREND_DAYS}"
    val (title, text) = when (wellbeing.chronic) {
        ChronicStress.UNKNOWN -> "Encore trop peu de jours mesurés" to
            "Il faut au moins ${Wellbeing.MIN_TREND_DAYS} jours de mesures sur 4 semaines pour dire si le stress " +
            "s'installe. Tu en as ${wellbeing.measuredDays}."
        ChronicStress.NONE -> "Pas de stress installé" to
            "$counted : ton stress redescend. Boucle parle de stress installé à partir de " +
            "${Wellbeing.SETTLED_ELEVATED_DAYS} jours sur ${Wellbeing.TREND_DAYS}."
        ChronicStress.WATCH -> "Stress fréquent" to
            "$counted. Ce n'est pas encore un stress installé, mais ton corps redescend moins souvent : " +
            "prévois de vraies pauses et des nuits plus longues."
        ChronicStress.SETTLED -> "Stress installé" to
            "$counted. Un stress qui dure fatigue le corps et entretient une inflammation de fond. Lève le pied " +
            "dès que tu peux, et parles-en à un médecin si tu te sens épuisé ou dépassé."
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MetricPalette.Wellbeing.container, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = BoucleType.rowTitle.copy(fontWeight = FontWeight.ExtraBold), color = MetricPalette.Wellbeing.onContainer)
        Text(text, style = BoucleType.body, color = MetricPalette.Wellbeing.onContainer)
    }
}

/** Carte « Ce qui compose ton score » : une ligne par composante mesurée. */
@Composable
private fun WellbeingComponents(score: WellbeingScore, modifier: Modifier = Modifier) {
    val rows = buildList<@Composable () -> Unit> {
        score.meanStress?.let { mean ->
            add {
                WellbeingComponentRow(
                    title = "Stress en journée",
                    value = mean.toString(),
                    score = score.stress,
                    detail = "Moyenne sur ${Wellbeing.SCORE_DAYS} jours · ${StressZone.of(mean.toDouble()).label.lowercase(Locale.FRENCH)}",
                )
            }
        }
        score.rmssdMs?.let { rmssd ->
            add {
                WellbeingComponentRow(
                    title = "VFC nocturne",
                    value = "$rmssd${SleepFormat.NBSP}ms",
                    score = score.hrv,
                    detail = score.rmssdBaselineMs?.let { "Référence $it${SleepFormat.NBSP}ms · ${score.hrvPosition}" }
                        ?: "Moyenne de tes dernières nuits",
                )
            }
        }
        score.restShare?.let { share ->
            add {
                WellbeingComponentRow(
                    title = "Retour au calme",
                    value = "${(share * 100).roundToInt()}${SleepFormat.NBSP}%",
                    score = score.calm,
                    detail = "Part du temps d'éveil mesuré en zone de repos",
                )
            }
        }
        score.sleep?.let { sleep ->
            add {
                WellbeingComponentRow(
                    title = "Sommeil",
                    value = sleep.toString(),
                    score = sleep,
                    detail = "Score moyen des ${Wellbeing.SCORE_DAYS} dernières nuits",
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
        Text("Ce qui compose ton score", style = BoucleType.sectionTitle, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(thickness = 1.dp, color = BoucleColors.Divider)
            row()
        }
    }
}

@Composable
private fun WellbeingComponentRow(title: String, value: String, score: Int?, detail: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = BoucleType.bodyStrong, modifier = Modifier.alignByBaseline())
            Text(value, style = BoucleType.valueM, modifier = Modifier.alignByBaseline())
        }
        score?.let { SegmentedBar(it / 100f, MetricPalette.Wellbeing.accent, ChartColors.WellbeingTrack) }
        Text(detail, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
    }
}

/**
 * « Comprendre » : quatre rubriques dépliables, une seule ouverte à la fois. Celle sur le stress
 * chronique et l'inflammation est ouverte à l'arrivée.
 */
@Composable
private fun WellbeingExplainer(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf<Int?>(1) }
    val middle = Radii(6.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Comprendre",
            style = BoucleType.sectionTitle,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
        )
        ExplainerItem("La VFC, c'est quoi ?", Radii.ListTop, open == 0, { open = if (open == 0) null else 0 }) {
            ExplainerText(
                "Ton cœur ne bat pas comme un métronome : l'écart entre deux battements change sans cesse de quelques " +
                    "millisecondes. C'est la variabilité de la fréquence cardiaque (VFC). Au calme, le « frein » de ton " +
                    "système nerveux (le nerf vague) domine et ces écarts sont grands. Sous stress, l'« accélérateur » " +
                    "prend la main : le cœur bat plus régulièrement et la VFC baisse. Boucle compare ta VFC à ta propre " +
                    "normale des 7 derniers jours.",
            )
        }
        ExplainerItem("Stress chronique et inflammation", middle, open == 1, { open = if (open == 1) null else 1 }) {
            ExplainerText(
                "Un pic de stress est normal : il mobilise ton énergie, puis il retombe. Le problème, c'est le stress " +
                    "qui ne redescend plus pendant des semaines. Le cortisol reste alors élevé et ton corps finit par " +
                    "moins bien y répondre : le système immunitaire libère trop de protéines inflammatoires.",
            )
            ProteinChips()
            ExplainerText(
                "Cette inflammation discrète mais permanente est associée, sur le long terme, à plus de maladies " +
                    "cardiovasculaires, de diabète de type 2 et de dépression. Le nerf vague freine normalement " +
                    "l'inflammation : une VFC basse pendant longtemps est le signe que ce frein travaille moins.",
            )
        }
        ExplainerItem("Et la surcharge mentale ?", middle, open == 2, { open = if (open == 2) null else 2 }) {
            ExplainerText(
                "Ton corps ne distingue pas un danger physique d'une tête trop pleine : trop de tâches en même temps, " +
                    "des décisions en continu, des ruminations, aucune vraie pause. Il réagit de la même façon, même " +
                    "assis à un bureau. Une charge mentale qui dure entretient donc le même mécanisme que le stress " +
                    "chronique. C'est pourquoi Boucle regarde surtout le stress mesuré quand tu ne bouges pas.",
            )
        }
        ExplainerItem("Ce que le score ne dit pas", Radii.ListBottom, open == 3, { open = if (open == 3) null else 3 }) {
            ExplainerText(
                "Boucle ne mesure ni le cortisol ni l'inflammation : seule une prise de sang le peut. Le score est une " +
                    "tendance, pas un diagnostic. L'alcool, une maladie, la caféine ou une nuit courte font aussi " +
                    "baisser la VFC. Si tu te sens épuisé ou dépassé depuis plusieurs semaines, parles-en à un médecin, " +
                    "quel que soit ton score.",
            )
        }
    }
}

/** Une rubrique : titre et chevron (60 dp au moins), puis le texte quand elle est ouverte. */
@Composable
private fun ExplainerItem(
    title: String,
    radii: Radii,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
        label = "chevron",
    )
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = radii,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (expanded) "Ouvert" else "Fermé" },
        onClick = onToggle,
        onClickLabel = if (expanded) "Replier" else "Déplier",
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = BoucleType.rowTitle, modifier = Modifier.weight(1f))
            Icon(
                BoucleIcons.ChevronDown,
                contentDescription = null,
                tint = BoucleColors.OnSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation),
            )
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ExplainerText(text: String) {
    Text(text, style = BoucleType.body.copy(lineHeight = BoucleType.body.fontSize * 1.5f), color = BODY_TEXT)
}

/** Les protéines inflammatoires citées dans l'explication. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProteinChips() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("IL-6", "TNF-α", "Protéine C-réactive (CRP)").forEach { name ->
            Text(
                name,
                style = BoucleType.smallStrong.copy(fontWeight = FontWeight.Bold),
                color = MetricPalette.Wellbeing.onContainer,
                modifier = Modifier
                    .background(MetricPalette.Wellbeing.container, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Pas de bien-être : aucune mesure de VFC sur la période. */
@Composable
private fun WellbeingEmpty(modifier: Modifier = Modifier) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.Card,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Pas encore de score de bien-être", style = BoucleType.rowTitle)
        Text(
            "Le score se calcule à partir de la variabilité de ta fréquence cardiaque, de jour comme de nuit. " +
                "Porte le bracelet quelques jours et synchronise-le pour le voir apparaître.",
            style = BoucleType.small,
            color = BoucleColors.OnSurfaceVariant,
        )
    }
}
