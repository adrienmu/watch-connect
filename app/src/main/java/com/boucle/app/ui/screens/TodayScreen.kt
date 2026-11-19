package com.boucle.app.ui.screens

import com.boucle.app.ui.components.rememberHaptics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.domain.BodySummary
import com.boucle.app.domain.RecoveryScore
import com.boucle.app.domain.StressDay
import com.boucle.app.domain.Wellbeing
import com.boucle.app.domain.WellbeingDay
import androidx.compose.foundation.border
import com.boucle.app.domain.Vo2MaxEstimation
import com.boucle.app.domain.band
import com.boucle.app.ui.DayUiState
import com.boucle.app.domain.DailyGoals
import com.boucle.app.domain.StepDay
import com.boucle.app.domain.StepHistory
import com.boucle.app.ui.components.AnimatedNumber
import com.boucle.app.ui.components.ArcGauge
import com.boucle.app.ui.components.HalfGauge
import com.boucle.app.ui.components.LinearGauge
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.boucle.app.ui.components.BadgeShape
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.SharedKeys
import com.boucle.app.ui.components.sharedContainer
import com.boucle.app.ui.components.IconBadge
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.RowChevron
import com.boucle.app.ui.components.formatThousands
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.navigation.Destination
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.ChartColors
import com.boucle.app.ui.theme.MetricColors
import com.boucle.app.ui.theme.MetricPalette
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Plage de fréquence cardiaque représentée par la jauge de la tuile Cœur (bpm). */
private const val HEART_SCALE_MIN = 40f
private const val HEART_SCALE_MAX = 200f

private val STORED_SYNC = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM")

/**
 * Onglet Aujourd'hui : choix du jour semaine par semaine, tuile Pas (jauge, barres de la semaine, calories
 * actives et objectif d'activité), grille des quatre métriques clés et liste du poids et de la dernière
 * séance. Tirer vers le bas relance une synchro.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TodayScreen(
    state: DayUiState,
    history: StepHistory,
    lastSyncAt: String?,
    refreshing: Boolean,
    onSelectDate: (LocalDate) -> Unit,
    onRefresh: () -> Unit,
    onSyncClick: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val pullState = rememberPullToRefreshState()
    // Vibration au moment où le geste dépasse le seuil qui déclenche la synchronisation.
    val pullHaptics = rememberHaptics()
    val pastThreshold by remember { derivedStateOf { pullState.distanceFraction >= 1f } }
    LaunchedEffect(pastThreshold) { if (pastThreshold && !refreshing) pullHaptics.threshold() }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = pullState,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = pullState,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            TodayHeader(
                syncLabel = syncLabel(lastSyncAt, today),
                refreshing = refreshing,
                onSyncClick = onSyncClick,
                modifier = Modifier.staggeredEntrance(0),
            )
            TodayWeekSelector(
                selected = state.date,
                today = today,
                week = history.week(state.date),
                onSelect = onSelectDate,
                modifier = Modifier.staggeredEntrance(1),
            )
            val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
            val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = state,
                contentKey = { it.date },
                transitionSpec = {
                    // Un jour plus récent arrive par la droite, un jour plus ancien par la gauche.
                    val direction = if (targetState.date > initialState.date) 1 else -1
                    (slideInHorizontally(spatial) { direction * it / 4 } + fadeIn(effects))
                        .togetherWith(slideOutHorizontally(spatial) { -direction * it / 4 } + fadeOut(effects))
                        .using(SizeTransform(clip = false))
                },
                label = "day",
            ) { day ->
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (day.isEmpty) {
                        EmptyDayCard(onSyncClick, Modifier.staggeredEntrance(2))
                    } else {
                        ActivityHero(
                            day = day,
                            week = history.week(day.date),
                            goals = history.goals.on(day.date),
                            streak = history.currentStreak(today),
                            today = today,
                            onClick = { onNavigate(Destination.Activity) },
                            modifier = Modifier.sharedContainer(SharedKeys.ACTIVITY, Radii.Hero, entranceIndex = 2),
                        )
                        MetricGrid(day, onNavigate, Modifier.staggeredEntrance(3))
                        DayList(day, onNavigate, Modifier.staggeredEntrance(4))
                    }
                    Text(
                        syncLabel(lastSyncAt, today),
                        style = BoucleType.caption,
                        color = BoucleColors.OnSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .staggeredEntrance(5),
                    )
                }
            }
        }
    }
}

/** Titre de l'onglet et bouton de synchronisation, qui devient un indicateur pendant la synchro. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TodayHeader(syncLabel: String, refreshing: Boolean, onSyncClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Aujourd'hui", style = BoucleType.screenTitle, color = BoucleColors.OnSurface, modifier = Modifier.weight(1f))
        val haptics = rememberHaptics()
        Button(
            onClick = {
                haptics.tap()
                onSyncClick()
            },
            shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(16.dp), pressedShape = RoundedCornerShape(10.dp)),
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = "Synchroniser le bracelet"
                    stateDescription = if (refreshing) "Synchronisation en cours" else syncLabel
                },
            colors = ButtonDefaults.buttonColors(
                containerColor = BoucleColors.PrimaryContainer,
                contentColor = BoucleColors.OnPrimaryContainer,
            ),
            contentPadding = PaddingValues(0.dp),
        ) {
            AnimatedContent(targetState = refreshing, label = "syncIcon") { syncing ->
                if (syncing) {
                    LoadingIndicator(Modifier.size(32.dp), color = BoucleColors.OnPrimaryContainer)
                } else {
                    Icon(BoucleIcons.Sync, contentDescription = null, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/**
 * Tuile Pas (bloc vert plein) : jauge en arc de l'objectif de pas, pas du jour, barres de la semaine affichée,
 * puis calories actives et objectif d'activité en jauges linéaires.
 */
@Composable
private fun ActivityHero(
    day: DayUiState,
    week: List<StepDay>,
    goals: DailyGoals,
    streak: Int,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = day.activity
    val steps = activity?.steps
    val stepDay = StepDay(day.date, steps, goals.steps)
    val activeCalories = activity?.activityCalories?.let { it + (activity.trainingCalories ?: 0) }
    val goalRatio = activity?.achievedActivity?.let { achieved ->
        activity.activityGoal?.takeIf { it > 0f }?.let { achieved / it }
    }
    ExpressiveSurface(
        color = BoucleColors.Primary,
        radii = Radii.Hero,
        modifier = modifier.fillMaxWidth(),
        contentColor = BoucleColors.OnPrimary,
        onClick = onClick,
        onClickLabel = "Voir l'historique des pas",
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Pas · objectif ${formatThousands(goals.steps)}",
                style = BoucleType.bodyStrong.copy(fontWeight = FontWeight.Bold),
                color = ChartColors.RingSteps,
                modifier = Modifier.weight(1f),
            )
            if (streak >= 2) StreakChip(streak)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ArcGauge(
                fraction = stepDay.fraction,
                color = ChartColors.RingSteps,
                trackColor = ChartColors.RingTrack,
                modifier = Modifier
                    .size(112.dp)
                    .clearAndSetSemantics { },
                strokeWidth = 12.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row {
                        AnimatedNumber(Math.round(stepDay.fraction * 100), BoucleType.valueL.copy(fontWeight = FontWeight.ExtraBold), Modifier.alignByBaseline())
                        Text("${SleepFormat.NBSP}%", style = BoucleType.smallStrong, modifier = Modifier.alignByBaseline())
                    }
                    Text("de l'objectif", style = BoucleType.caption.copy(fontWeight = FontWeight.SemiBold), color = ChartColors.RingSteps)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (steps != null) {
                    AnimatedNumber(steps, BoucleType.heroXS)
                } else {
                    Text("—", style = BoucleType.heroXS)
                }
                Text(
                    when {
                        steps == null -> "Aucune mesure de pas"
                        stepDay.reached -> "Objectif atteint"
                        else -> "Encore ${StepsFormat.steps(goals.steps - steps)}"
                    },
                    style = BoucleType.body.copy(fontWeight = FontWeight.SemiBold),
                    color = ChartColors.RingSteps,
                )
            }
        }
        StepBars(days = week, selected = day.date, today = today, height = 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroGauge(
                label = "Calories actives",
                value = activeCalories?.let { "${formatThousands(it)} / ${formatThousands(goals.activeCalories)}${SleepFormat.NBSP}kcal" } ?: "—",
                fraction = activeCalories?.let { it.toFloat() / goals.activeCalories },
                color = ChartColors.RingCalories,
            )
            HeroGauge(
                label = "Objectif d'activité",
                value = goalRatio?.let { "${Math.round(it * 100)}${SleepFormat.NBSP}%" } ?: "—",
                fraction = goalRatio,
                color = ChartColors.RingGoal,
            )
        }
    }
}

/** Jauge linéaire de la tuile Pas : libellé à gauche, valeur à droite, barre dessous. */
@Composable
private fun HeroGauge(label: String, value: String, fraction: Float?, color: Color) {
    Column(Modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, style = BoucleType.smallStrong, modifier = Modifier.weight(1f))
            Text(value, style = BoucleType.smallStrong)
        }
        LinearGauge(fraction ?: 0f, color, ChartColors.RingTrack)
    }
}

/** Pastille « Série de N j » : nombre de jours d'affilée à l'objectif de pas. */
@Composable
private fun StreakChip(days: Int) {
    Row(
        Modifier
            .height(32.dp)
            .background(ChartColors.StepsInset, CircleShape)
            .padding(start = 4.dp, end = 12.dp)
            .clearAndSetSemantics { contentDescription = "Série de $days jours à l'objectif de pas" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconBadge(
            BoucleIcons.Flame,
            ChartColors.RingCalories,
            MetricPalette.Energy.onContainer,
            shape = BadgeShape.Sunny,
            size = 26.dp,
            iconSize = 14.dp,
        )
        Text("Série de $days${SleepFormat.NBSP}j", style = BoucleType.smallStrong.copy(fontWeight = FontWeight.Bold))
    }
}

/** Tuile large du bien-être, puis grille 2 × 2 : sommeil, récupération, Body Battery, fréquence cardiaque. */
@Composable
private fun MetricGrid(day: DayUiState, onNavigate: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val sleep = day.sleep
    val sleepScore = day.sleepScore
    val recovery = day.recovery
    val battery = day.battery
    val asleep = sleep?.let(SleepFormat::asleepSec)?.let(SleepFormat::duration)
    val restingHeartRate = recovery?.nightHeartRate
        ?: day.recharge?.meanRriMs?.takeIf { it > 0 }?.let { Math.round(60_000f / it) }
        ?: day.heartRate?.min

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WellbeingTile(day.wellbeing, day.date) { onNavigate(Destination.Wellbeing) }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MetricTile(
                label = "Sommeil",
                icon = BoucleIcons.Moon,
                colors = MetricPalette.Sleep,
                shape = BadgeShape.Cookie,
                radii = Radii.GridMiddle,
                value = sleepScore?.total,
                gauge = sleepScore?.total?.let { it / 100f },
                detail = when {
                    sleepScore != null -> listOfNotNull(SleepFormat.bandLabel(sleepScore.band), asleep).joinToString(" · ")
                    asleep != null -> "$asleep dormies"
                    else -> null
                },
                hint = "Aucune nuit enregistrée",
                sharedKey = SharedKeys.SLEEP,
                onClick = { onNavigate(Destination.Sleep) },
            )
            MetricTile(
                label = "Récupération",
                icon = BoucleIcons.Shield,
                colors = MetricPalette.Activity,
                shape = BadgeShape.Clover,
                radii = Radii.GridMiddle,
                value = recovery?.total,
                gauge = recovery?.total?.let { it / 100f },
                detail = recovery?.let { score ->
                    listOfNotNull(score.label, score.rmssdMs?.let { "VFC $it${SleepFormat.NBSP}ms" }).joinToString(" · ")
                },
                hint = "Mesures de nuit manquantes",
                sharedKey = SharedKeys.RECOVERY,
                onClick = { onNavigate(Destination.Recovery) },
            )
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MetricTile(
                label = "Énergie",
                icon = BoucleIcons.Bolt,
                colors = MetricPalette.Energy,
                shape = BadgeShape.Sunny,
                radii = Radii.GridBottomStart,
                value = battery?.current,
                gauge = battery?.current?.let { it / 100f },
                detail = battery?.let { it.wake?.let { wake -> "$wake au réveil" } ?: "Niveau actuel" },
                hint = "Pas assez de mesures",
                sharedKey = SharedKeys.ENERGY,
                onClick = { onNavigate(Destination.Energy) },
            )
            MetricTile(
                label = "Cœur",
                icon = BoucleIcons.Heart,
                colors = MetricPalette.Heart,
                shape = BadgeShape.Flower,
                radii = Radii.GridBottomEnd,
                value = restingHeartRate,
                unit = "bpm",
                range = day.heartRate?.let { it.min.toFloat()..it.max.toFloat() },
                detail = restingHeartRate?.let { listOfNotNull("Au repos", day.heartRate?.max?.let { "max $it" }).joinToString(" · ") },
                hint = "Aucune mesure",
                sharedKey = SharedKeys.HEART,
                onClick = { onNavigate(Destination.Heart) },
            )
        }
    }
}

/**
 * Tuile large du bien-être : score sur 7 jours et son palier, stress moyen de chacun des 7 derniers
 * jours, puis une phrase sur la journée. Sans score, elle garde sa place et affiche « — ».
 */
@Composable
private fun WellbeingTile(wellbeing: WellbeingDay?, date: LocalDate, onClick: () -> Unit) {
    val colors = MetricPalette.Wellbeing
    val score = wellbeing?.score
    val detail = listOfNotNull(
        wellbeing?.today?.let { "${it.zone.phrase} ${if (date == LocalDate.now()) "aujourd'hui" else "ce jour-là"}" },
        score?.hrvPosition?.let { "VFC $it" },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    ExpressiveSurface(
        color = colors.container,
        radii = Radii.GridTop,
        modifier = Modifier
            .fillMaxWidth()
            .sharedContainer(SharedKeys.WELLBEING, Radii.GridTop, entrance = false),
        contentColor = colors.onContainer,
        onClick = onClick,
        onClickLabel = "Voir le détail",
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBadge(BoucleIcons.Wave, colors.accent, Color.White, shape = BadgeShape.Pentagon, size = 32.dp, iconSize = 16.dp)
            Text("Bien-être · ${Wellbeing.SCORE_DAYS} jours", style = BoucleType.smallStrong, modifier = Modifier.weight(1f))
            score?.let {
                Text(
                    it.level.label,
                    style = BoucleType.smallStrong.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .background(BoucleColors.Surface, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (score != null) AnimatedNumber(score.total, BoucleType.heroS) else Text("—", style = BoucleType.heroS)
            wellbeing?.let { WeekStress(it.week, date, Modifier.weight(1f)) }
        }
        Text(
            detail ?: "Pas assez de mesures de VFC",
            style = BoucleType.small,
            color = if (detail == null) colors.onContainer.copy(alpha = 0.7f) else Color.Unspecified,
        )
    }
}

/** Stress moyen des 7 derniers jours : une barre par jour, plus haute et plus foncée quand le stress monte. */
@Composable
private fun WeekStress(week: List<StressDay?>, date: LocalDate, modifier: Modifier = Modifier) {
    val ink = MetricPalette.Wellbeing.onContainer
    val summary = week.joinToString { it?.zone?.label?.lowercase(Locale.FRENCH) ?: "pas de mesure" }
    Column(
        modifier.clearAndSetSemantics { contentDescription = "Stress moyen des 7 derniers jours : $summary" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            week.forEachIndexed { index, day ->
                val shape = RoundedCornerShape(6.dp)
                val height = day?.let { (34 * it.mean / WEEK_STRESS_FULL).toFloat().coerceIn(8f, 34f).dp } ?: 6.dp
                Box(
                    Modifier
                        .weight(1f)
                        .height(height)
                        .background(day?.zone?.color ?: BoucleColors.Surface, shape)
                        .then(if (index == week.lastIndex && day != null) Modifier.border(2.dp, ink, shape) else Modifier),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            week.indices.forEach { index ->
                val weekday = date.minusDays((week.lastIndex - index).toLong()).dayOfWeek.value
                Text(
                    DAY_INITIALS[weekday - 1].toString(),
                    style = if (index == week.lastIndex) BoucleType.captionStrong.copy(fontWeight = FontWeight.ExtraBold) else BoucleType.captionStrong,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Stress moyen d'une journée qui remplit la hauteur des barres de la tuile. */
private const val WEEK_STRESS_FULL = 75.0
private const val DAY_INITIALS = "LMMJVSD"

/**
 * Tuile de la grille : macaron de la métrique, valeur, puis [detail]. Un score sur 100 s'accompagne d'une
 * demi-jauge ([gauge], de 0 à 1) ; la fréquence cardiaque d'une barre qui situe la plage du jour ([range], en bpm).
 * Sans [value], la tuile garde sa place et affiche « — » avec [hint] en sourdine.
 */
@Composable
private fun RowScope.MetricTile(
    label: String,
    icon: ImageVector,
    colors: MetricColors,
    shape: BadgeShape,
    radii: Radii,
    value: Int?,
    detail: String?,
    hint: String,
    sharedKey: String,
    onClick: () -> Unit,
    unit: String? = null,
    gauge: Float? = null,
    range: ClosedFloatingPointRange<Float>? = null,
) {
    val track = colors.accent.copy(alpha = 0.22f)
    ExpressiveSurface(
        color = colors.container,
        radii = radii,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .sharedContainer(sharedKey, radii, entrance = false),
        contentColor = colors.onContainer,
        onClick = onClick,
        onClickLabel = "Voir le détail",
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBadge(icon, colors.accent, Color.White, shape = shape, size = 32.dp, iconSize = 16.dp)
            Text(label, style = BoucleType.smallStrong)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (value != null) {
                AnimatedNumber(value, BoucleType.tileValue, Modifier.alignByBaseline())
                unit?.let { Text("${SleepFormat.NBSP}$it", style = BoucleType.bodyStrong, modifier = Modifier.alignByBaseline()) }
            } else {
                Text("—", style = BoucleType.tileValue)
            }
            if (gauge != null) {
                Spacer(Modifier.weight(1f))
                HalfGauge(gauge, colors.accent, track, Modifier.clearAndSetSemantics { })
            }
        }
        if (range != null) {
            val span = HEART_SCALE_MAX - HEART_SCALE_MIN
            LinearGauge(
                fraction = (range.endInclusive - HEART_SCALE_MIN) / span,
                color = colors.accent,
                trackColor = track,
                modifier = Modifier.clearAndSetSemantics { },
                start = (range.start - HEART_SCALE_MIN) / span,
            )
        }
        val muted = value == null && detail == null
        Text(
            detail ?: hint,
            style = BoucleType.small,
            color = if (muted) colors.onContainer.copy(alpha = 0.7f) else Color.Unspecified,
        )
    }
}

/** Liste groupée : dernière pesée et dernière séance du jour. Les lignes sans donnée sont masquées. */
@Composable
private fun DayList(day: DayUiState, onNavigate: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val body = day.body
    val session = day.sessions.maxByOrNull { it.startTime.orEmpty() }
    if (body == null && session == null) return
    // Une ligne seule reprend les quatre grands coins ; à deux, elles forment un groupe.
    val alone = body == null || session == null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        body?.let {
            WeightRow(it, day.date, if (alone) Radii.Tile else Radii.ListTop) { onNavigate(Destination.Body) }
        }
        session?.let {
            SessionRow(it, if (alone) Radii.Tile else Radii.ListBottom) { onNavigate(Destination.Activity) }
        }
    }
}

@Composable
private fun WeightRow(body: BodySummary, date: LocalDate, radii: Radii, onClick: () -> Unit) {
    val weighed = when (body.weighedOn) {
        date -> if (date == LocalDate.now()) "Pesée aujourd'hui" else "Pesée ce jour"
        date.minusDays(1) -> "Pesée la veille"
        else -> "Pesée le ${body.weighedOn.format(DAY_MONTH)}"
    }
    val change = body.change7Days?.let { delta ->
        if (abs(delta) < 0.05) "stable sur 7 jours" else "${signed(delta, 1)}${SleepFormat.NBSP}kg sur 7 jours"
    }
    ListRow(
        title = "Poids · ${decimal(body.weightKg, 1)}${SleepFormat.NBSP}kg",
        detail = listOfNotNull(weighed, change).joinToString(" · "),
        radii = radii,
        onClick = onClick,
        sharedKey = SharedKeys.BODY,
    ) {
        IconBadge(BoucleIcons.Scale, MetricPalette.Body.container, MetricPalette.Body.onContainer, shape = BadgeShape.Clover)
    }
}

@Composable
private fun SessionRow(session: TrainingSessionEntity, radii: Radii, onClick: () -> Unit) {
    val name = session.name?.takeIf { it.isNotBlank() }
        ?: if (session.sportId == Vo2MaxEstimation.RUNNING_SPORT_ID) "Course" else "Séance"
    val start = session.startTime?.let { runCatching { LocalDateTime.parse(it).format(HOUR) }.getOrNull() }
    val distance = session.distanceMeters?.takeIf { it > 0f }?.let { "${decimal(it / 1000.0, 2)}${SleepFormat.NBSP}km" }
    val calories = session.calories?.let { "${formatThousands(it)}${SleepFormat.NBSP}kcal" }
    ListRow(
        title = listOfNotNull(name, session.durationSec?.let { SleepFormat.duration(it.toLong()) }).joinToString(" · "),
        detail = listOfNotNull(start, distance, calories).joinToString(" · "),
        radii = radii,
        onClick = onClick,
    ) {
        IconBadge(
            BoucleIcons.Pulse,
            MetricPalette.Activity.container,
            MetricPalette.Activity.onContainer,
            shape = BadgeShape.Cookie,
        )
    }
}

@Composable
private fun ListRow(
    title: String,
    detail: String,
    radii: Radii,
    onClick: () -> Unit,
    sharedKey: String? = null,
    badge: @Composable () -> Unit,
) {
    val shared = if (sharedKey != null) Modifier.sharedContainer(sharedKey, radii, entrance = false) else Modifier
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = radii,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(shared),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            badge()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = BoucleType.rowTitle)
                if (detail.isNotEmpty()) Text(detail, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
            }
            RowChevron()
        }
    }
}

/** Carte affichée à la place du contenu quand le jour n'a aucune mesure. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyDayCard(onSyncClick: () -> Unit, modifier: Modifier = Modifier) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.Card,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(
            BoucleIcons.Sync,
            BoucleColors.PrimaryContainer,
            BoucleColors.OnPrimaryContainer,
            shape = BadgeShape.Cookie,
            size = 56.dp,
            iconSize = 24.dp,
        )
        Text("Aucune donnée pour ce jour.", style = BoucleType.sectionTitle, textAlign = TextAlign.Center)
        Text(
            "Synchronise ton bracelet pour voir tes mesures.",
            style = BoucleType.body,
            color = BoucleColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val haptics = rememberHaptics()
        Button(
            onClick = {
                haptics.tap()
                onSyncClick()
            },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("Synchroniser", style = BoucleType.button)
        }
    }
}

/** Libellé de catégorie sur l'échelle en quatre paliers de l'écran Récupération. */
private val RecoveryScore.label: String
    get() = when {
        total >= 85 -> "Pleine forme"
        total >= 67 -> "Bonne"
        total >= 34 -> "Modérée"
        else -> "Basse"
    }

/** « Synchronisé à 08:12 » le jour même, « Synchronisé le 14/11 à 08:12 » sinon. */
private fun syncLabel(lastSyncAt: String?, today: LocalDate): String {
    val at = lastSyncAt?.let { runCatching { LocalDateTime.parse(it, STORED_SYNC) }.getOrNull() }
        ?: return "Jamais synchronisé"
    return if (at.toLocalDate() == today) {
        "Synchronisé à ${at.format(HOUR)}"
    } else {
        "Synchronisé le ${at.format(DAY_MONTH)} à ${at.format(HOUR)}"
    }
}

private fun decimal(value: Double, digits: Int): String = "%.${digits}f".format(Locale.FRENCH, value)

/** Écart signé avec un vrai signe moins : « +0,3 », « −0,3 ». */
private fun signed(value: Double, digits: Int): String = (if (value > 0) "+" else "−") + decimal(abs(value), digits)
