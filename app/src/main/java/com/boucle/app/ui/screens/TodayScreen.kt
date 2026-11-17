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
import com.boucle.app.domain.Vo2MaxEstimation
import com.boucle.app.domain.band
import com.boucle.app.ui.DayUiState
import com.boucle.app.ui.components.AnimatedNumber
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

/** Objectif de pas fixe, en attendant un réglage dans le profil. */
private const val STEP_GOAL = 10_000

/** Objectif de calories actives fixe (kcal), en attendant un réglage dans le profil. */
private const val ACTIVE_CALORIE_GOAL = 600

private val STORED_SYNC = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM")

/**
 * Onglet Aujourd'hui : choix du jour, bloc héros d'activité avec ses anneaux, grille des quatre
 * métriques clés et liste du poids et de la dernière séance. Tirer vers le bas relance une synchro.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TodayScreen(
    state: DayUiState,
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
            TodayDaySelector(
                selected = state.date,
                today = today,
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
                            day,
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

/** Bloc vert plein : anneaux, pas du jour, calories actives et objectif d'activité. */
@Composable
private fun ActivityHero(day: DayUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val activity = day.activity
    val steps = activity?.steps
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
        onClickLabel = "Voir la forme",
        contentPadding = PaddingValues(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TodayRings(
                steps = steps?.let { it.toFloat() / STEP_GOAL },
                calories = activeCalories?.let { it.toFloat() / ACTIVE_CALORIE_GOAL },
                activityGoal = goalRatio,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Pas · sur ${formatThousands(STEP_GOAL)}",
                    style = BoucleType.body.copy(fontWeight = FontWeight.SemiBold),
                    color = ChartColors.RingSteps,
                )
                if (steps != null) {
                    AnimatedNumber(steps, BoucleType.heroXS)
                } else {
                    Text("—", style = BoucleType.heroXS)
                }
                Spacer(Modifier.height(4.dp))
                HeroLegend(
                    color = ChartColors.RingCalories,
                    text = activeCalories?.let { "${formatThousands(it)}${SleepFormat.NBSP}kcal actives" }
                        ?: "Calories actives indisponibles",
                )
                HeroLegend(
                    color = ChartColors.RingGoal,
                    text = goalRatio?.let { "${Math.round(it * 100)}${SleepFormat.NBSP}% de l'objectif" }
                        ?: "Objectif d'activité indisponible",
                )
            }
        }
    }
}

@Composable
private fun HeroLegend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(text, style = BoucleType.smallStrong)
    }
}

/** Grille 2 × 2 : sommeil, récupération, Body Battery, fréquence cardiaque. */
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
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MetricTile(
                label = "Sommeil",
                icon = BoucleIcons.Moon,
                colors = MetricPalette.Sleep,
                radii = Radii.GridTopStart,
                value = sleepScore?.total,
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
                radii = Radii.GridTopEnd,
                value = recovery?.total,
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
                radii = Radii.GridBottomStart,
                value = battery?.current,
                detail = battery?.let { it.wake?.let { wake -> "$wake au réveil" } ?: "Niveau actuel" },
                hint = "Pas assez de mesures",
                sharedKey = SharedKeys.ENERGY,
                onClick = { onNavigate(Destination.Energy) },
            )
            MetricTile(
                label = "Fréquence cardiaque",
                icon = BoucleIcons.Heart,
                colors = MetricPalette.Heart,
                radii = Radii.GridBottomEnd,
                value = restingHeartRate,
                unit = "bpm",
                detail = restingHeartRate?.let { listOfNotNull("Au repos", day.heartRate?.max?.let { "max $it" }).joinToString(" · ") },
                hint = "Aucune mesure",
                sharedKey = SharedKeys.HEART,
                onClick = { onNavigate(Destination.Heart) },
            )
        }
    }
}

/**
 * Tuile de la grille. Sans [value], elle garde sa place et affiche « — » avec [hint] en sourdine ;
 * [detail] complète la valeur quand elle existe.
 */
@Composable
private fun RowScope.MetricTile(
    label: String,
    icon: ImageVector,
    colors: MetricColors,
    radii: Radii,
    value: Int?,
    detail: String?,
    hint: String,
    sharedKey: String,
    onClick: () -> Unit,
    unit: String? = null,
) {
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
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = BoucleType.smallStrong)
        }
        if (value != null) {
            Row {
                AnimatedNumber(value, BoucleType.tileValue, Modifier.alignByBaseline())
                unit?.let { Text("${SleepFormat.NBSP}$it", style = BoucleType.bodyStrong, modifier = Modifier.alignByBaseline()) }
            }
        } else {
            Text("—", style = BoucleType.tileValue)
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
        IconBadge(BoucleIcons.Scale, MetricPalette.Body.container, MetricPalette.Body.onContainer)
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
