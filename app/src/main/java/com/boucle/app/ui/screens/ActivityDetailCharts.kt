package com.boucle.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boucle.app.domain.HrZoneTime
import com.boucle.app.domain.KmSplit
import com.boucle.app.domain.RouteStats
import com.boucle.app.domain.TrackPoint
import com.boucle.app.ui.components.animatedValue
import com.boucle.app.ui.components.rememberReducedMotion
import com.boucle.app.ui.screens.ActivityFormat.NBSP
import com.boucle.app.ui.theme.RouteColors
import kotlin.math.roundToInt

/*
 * Contenu de chaque onglet du détail d'activité (Allure, Fréq. card., Altitude) : courbes le long de la distance,
 * temps par km, zones cardiaques, dénivelé par km.
 */

/** Mise en forme des valeurs d'une séance. */
internal object RouteFormat {
    /** 330 s/km → « 5'30" ». */
    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || !secPerKm.isFinite()) return "—"
        val total = secPerKm.roundToInt()
        return "${total / 60}'%02d\"".format(total % 60)
    }

    /** Durée de séance : « 46:18 », « 1:02:05 ». */
    fun clock(seconds: Number): String {
        val s = seconds.toDouble().roundToInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** 8421 m → « 8,42 ». */
    fun km(meters: Double): String = ActivityFormat.decimal(meters / 1000, 2)

    /** Libellé d'un kilomètre : « 3 », ou « 0,42 » pour la fraction finale. */
    fun splitLabel(split: KmSplit): String =
        if (split.distanceMeters >= 999.0) split.number.toString() else ActivityFormat.decimal(split.distanceMeters / 1000, 2)

    fun meters(value: Double): String = "${value.roundToInt()}${NBSP}m"

    /** « 1er », « 2e »… */
    fun ordinal(n: Int): String = if (n == 1) "1er" else "${n}e"
}

/** Carte de section du détail (fond gris-lavande, coins de 28 dp). */
@Composable
internal fun DetailCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    gap: Int = 12,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(RouteColors.Container, RoundedCornerShape(28.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(title, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = RouteColors.OnSurface)
            trailing?.let { Text(it, style = TextStyle(fontSize = 12.sp), color = RouteColors.OnSurfaceVariant) }
        }
        content()
    }
}

/** Petites valeurs en tête de carte (« Moyenne 152 », « D+ 64 m »…). */
@Composable
internal fun MiniStats(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (items.size > 3) 6.dp else 8.dp)) {
        items.forEach { (label, value) ->
            Column(
                Modifier
                    .weight(1f)
                    .background(RouteColors.Background, RoundedCornerShape(16.dp))
                    .padding(horizontal = if (items.size > 3) 10.dp else 12.dp, vertical = 10.dp),
            ) {
                Text(label, style = TextStyle(fontSize = 12.sp), color = RouteColors.OnSurfaceVariant)
                Text(
                    value,
                    style = TextStyle(fontSize = if (items.size > 3) 18.sp else 20.sp, fontWeight = FontWeight.ExtraBold),
                    color = RouteColors.OnSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Légende d'axe horizontal : départ, milieu, distance totale. */
@Composable
internal fun DistanceAxis(stats: RouteStats) {
    val total = stats.distanceMeters
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("0${NBSP}km", "${(total / 2000).roundToInt()}${NBSP}km", "${RouteFormat.km(total)}${NBSP}km").forEach {
            Text(it, style = TextStyle(fontSize = 12.sp), color = RouteColors.OnSurfaceVariant)
        }
    }
}

@Composable
internal fun ChartLegend(items: List<Triple<String, Color, Boolean>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items.forEach { (label, color, line) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(width = 14.dp, height = if (line) 3.dp else 10.dp).background(color, RoundedCornerShape(if (line) 2.dp else 3.dp)))
                Text(label, style = TextStyle(fontSize = 12.sp), color = RouteColors.OnSurfaceVariant)
            }
        }
    }
}

/** Progression du dessin d'une courbe à son apparition : 1,2 s, décélération accentuée. */
@Composable
private fun rememberChartReveal(key: Any?): Animatable<Float, *> {
    val reduced = rememberReducedMotion()
    val reveal = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key) {
        if (!reduced) reveal.animateTo(1f, tween(1200, easing = EmphasizedDecelerate))
    }
    return reveal
}

/** Série (distance en fraction du total, valeur) réduite à [buckets] points moyennés. */
internal fun series(points: List<TrackPoint>, total: Double, buckets: Int = 120, value: (TrackPoint) -> Double?): List<Pair<Float, Float>> {
    if (points.isEmpty() || total <= 0) return emptyList()
    val sums = DoubleArray(buckets)
    val counts = IntArray(buckets)
    points.forEach { p ->
        val v = value(p) ?: return@forEach
        val b = ((p.distanceMeters / total) * (buckets - 1)).roundToInt().coerceIn(0, buckets - 1)
        sums[b] += v
        counts[b]++
    }
    return (0 until buckets).filter { counts[it] > 0 }.map { (it.toFloat() / (buckets - 1)) to (sums[it] / counts[it]).toFloat() }
}

/**
 * Courbe le long de la distance : aire facultative (dessinée d'un coup) et ligne qui se dessine de gauche à droite.
 * [lineRange]/[areaRange] : valeurs mises en bas et en haut ; une plage inversée met les petites valeurs en haut.
 */
@Composable
internal fun DistanceChart(
    description: String,
    line: List<Pair<Float, Float>>,
    lineRange: Pair<Float, Float>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    area: List<Pair<Float, Float>> = emptyList(),
    areaRange: Pair<Float, Float> = lineRange,
    areaColor: Color = Color.Transparent,
    lineFill: Color? = null,
    dashedValue: Float? = null,
    dashedLabel: String? = null,
    peak: Pair<Pair<Float, Float>, String>? = null,
) {
    val reveal = rememberChartReveal(line.size to lineRange)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = RouteColors.OnSurfaceVariant)
    val peakStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = lineColor)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .clipToBounds()
            .semantics { contentDescription = description },
    ) {
        val top = 8.dp.toPx()
        val h = size.height - top
        fun y(v: Float, range: Pair<Float, Float>): Float {
            val (low, high) = range
            val t = if (high == low) 0.5f else (v - low) / (high - low)
            return top + h * (1 - t.coerceIn(0f, 1f)) * 0.92f + h * 0.04f
        }
        fun path(points: List<Pair<Float, Float>>, range: Pair<Float, Float>, closed: Boolean) = Path().apply {
            points.forEachIndexed { i, (fx, v) ->
                val px = fx * size.width
                if (i == 0) moveTo(px, y(v, range)) else lineTo(px, y(v, range))
            }
            if (closed && points.isNotEmpty()) {
                lineTo(points.last().first * size.width, size.height)
                lineTo(points.first().first * size.width, size.height)
                close()
            }
        }
        if (area.size > 1) drawPath(path(area, areaRange, closed = true), areaColor)
        if (dashedValue != null) {
            val dy = y(dashedValue, lineRange)
            drawLine(
                RouteColors.Outline,
                Offset(0f, dy),
                Offset(size.width, dy),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
            dashedLabel?.let {
                val text = measurer.measure(it, labelStyle)
                drawText(text, topLeft = Offset(size.width - text.size.width - 4.dp.toPx(), dy - text.size.height - 2.dp.toPx()))
            }
        }
        if (line.size > 1) {
            clipRect(right = size.width * reveal.value) {
                lineFill?.let { drawPath(path(line, lineRange, closed = true), it) }
                drawPath(
                    path(line, lineRange, closed = false),
                    lineColor,
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        peak?.let { (point, label) ->
            if (reveal.value >= point.first) {
                val c = Offset(point.first * size.width, y(point.second, lineRange))
                drawCircle(Color.White, 7.dp.toPx(), c)
                drawCircle(lineColor, 5.dp.toPx(), c)
                val text = measurer.measure(label, peakStyle)
                val x = (c.x + 8.dp.toPx()).coerceAtMost(size.width - text.size.width)
                drawText(text, topLeft = Offset(x, (c.y - text.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
            }
        }
    }
}

/** Temps par kilomètre : barre proportionnelle à la vitesse, kilomètre le plus rapide mis en valeur. */
@Composable
internal fun SplitsCard(stats: RouteStats, modifier: Modifier = Modifier) {
    val splits = stats.splits
    val slowest = splits.maxOf { it.paceSecPerKm }
    val fastest = splits.minOf { it.paceSecPerKm }
    DetailCard("Par kilomètre", modifier, gap = 10) {
        splits.forEachIndexed { i, split ->
            val best = i == stats.fastestSplitIndex
            val share = if (slowest > fastest) 0.4f + ((slowest - split.paceSecPerKm) / (slowest - fastest)).toFloat() * 0.6f else 1f
            val width = animatedValue(share)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clearAndSetSemantics {
                        contentDescription = "Kilomètre ${RouteFormat.splitLabel(split)} : ${RouteFormat.pace(split.paceSecPerKm)} par kilomètre" +
                            if (best) ", le plus rapide" else ""
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(RouteFormat.splitLabel(split), Modifier.width(36.dp), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = RouteColors.OnSurface)
                Box(Modifier.weight(1f).height(12.dp).background(RouteColors.ContainerHigh, RoundedCornerShape(6.dp))) {
                    Box(
                        Modifier
                            .fillMaxWidth(width.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(if (best) RouteColors.FastestSplit else RouteColors.Primary, RoundedCornerShape(6.dp)),
                    )
                }
                Text(
                    RouteFormat.pace(split.paceSecPerKm),
                    Modifier.width(48.dp),
                    style = TextStyle(fontSize = 14.sp, textAlign = TextAlign.End, fontWeight = if (best) FontWeight.Bold else FontWeight.Normal),
                    color = if (best) RouteColors.FastestSplit else RouteColors.OnSurface,
                )
            }
        }
        stats.fastestSplitIndex?.let { i ->
            val split = splits[i]
            Text(
                "Km le plus rapide : ${RouteFormat.ordinal(split.number)} km en ${RouteFormat.pace(split.paceSecPerKm)}",
                style = TextStyle(fontSize = 12.sp),
                color = RouteColors.OnSurfaceVariant,
            )
        }
    }
}

/** Libellés français des 5 zones cardiaques. */
private val ZONE_NAMES = listOf("Récupération", "Endurance", "Tempo", "Seuil", "Maximum")

/** Temps passé dans chaque zone : pastille numérotée, nom, plage en bpm, barre proportionnelle à la zone la plus longue. */
@Composable
internal fun HeartZonesCard(zones: List<HrZoneTime>, maxHr: Int, estimated: Boolean, modifier: Modifier = Modifier) {
    val longest = zones.maxOf { it.seconds }.coerceAtLeast(1)
    DetailCard("Zones cardiaques", modifier, gap = 10) {
        zones.forEachIndexed { i, zone ->
            val (container, content) = RouteColors.Zones[i]
            val range = when {
                zone.lowerBpm == null -> "<${NBSP}${zone.upperBpm}${NBSP}bpm"
                zone.upperBpm == null -> ">${NBSP}${zone.lowerBpm}${NBSP}bpm"
                else -> "${zone.lowerBpm}–${zone.upperBpm}${NBSP}bpm"
            }
            val share = animatedValue(zone.seconds.toFloat() / longest)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clearAndSetSemantics {
                        contentDescription = "Zone ${zone.number}, ${ZONE_NAMES[i]}, $range : ${RouteFormat.clock(zone.seconds)}"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(32.dp).background(container, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Text(zone.number.toString(), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = content)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(ZONE_NAMES[i], style = TextStyle(fontSize = 12.sp), color = RouteColors.OnSurfaceVariant)
                        Text(range, style = TextStyle(fontSize = 12.sp), color = RouteColors.OnSurfaceVariant)
                    }
                    Box(Modifier.fillMaxWidth().height(8.dp).background(RouteColors.ContainerHigh, RoundedCornerShape(4.dp))) {
                        // Une zone vide garde un petit trait, comme dans la maquette.
                        Box(
                            Modifier
                                .fillMaxWidth(share.coerceIn(0.03f, 1f))
                                .fillMaxHeight()
                                .background(container, RoundedCornerShape(4.dp)),
                        )
                    }
                }
                Text(
                    RouteFormat.clock(zone.seconds),
                    Modifier.width(44.dp),
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End),
                    color = RouteColors.OnSurface,
                )
            }
        }
        Text(
            if (estimated) {
                "Zones calculées sur une FC max estimée à $maxHr${NBSP}bpm d'après votre âge."
            } else {
                "Zones calculées sur une FC max de $maxHr${NBSP}bpm."
            },
            style = TextStyle(fontSize = 12.sp),
            color = RouteColors.OnSurfaceVariant,
        )
    }
}

/** Dénivelé par km : barres qui partent d'un axe central, montée à droite en vert, descente à gauche en gris-bleu. */
@Composable
internal fun ElevationSplitsCard(splits: List<KmSplit>, modifier: Modifier = Modifier) {
    val largest = splits.maxOf { maxOf(it.gainMeters, it.lossMeters) }.coerceAtLeast(1.0)
    DetailCard("Dénivelé par kilomètre", modifier, gap = 10) {
        splits.forEach { split ->
            val net = split.gainMeters - split.lossMeters
            val up = animatedValue((split.gainMeters / largest).toFloat())
            val down = animatedValue((split.lossMeters / largest).toFloat())
            val label = when {
                net.roundToInt() > 0 -> "+${net.roundToInt()}${NBSP}m"
                net.roundToInt() < 0 -> "−${-net.roundToInt()}${NBSP}m"
                else -> "0${NBSP}m"
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clearAndSetSemantics {
                        contentDescription = "Kilomètre ${RouteFormat.splitLabel(split)} : montée ${split.gainMeters.roundToInt()} m, " +
                            "descente ${split.lossMeters.roundToInt()} m"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(RouteFormat.splitLabel(split), Modifier.width(36.dp), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = RouteColors.OnSurface)
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (down > 0.005f) {
                            Box(
                                Modifier
                                    .fillMaxWidth(down.coerceIn(0f, 1f))
                                    .height(12.dp)
                                    .background(RouteColors.Descent, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)),
                            )
                        }
                    }
                    Box(Modifier.width(2.dp).height(20.dp).background(RouteColors.Outline))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (up > 0.005f) {
                            Box(
                                Modifier
                                    .fillMaxWidth(up.coerceIn(0f, 1f))
                                    .height(12.dp)
                                    .background(RouteColors.Climb, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)),
                            )
                        }
                    }
                }
                Text(label, Modifier.width(48.dp), style = TextStyle(fontSize = 14.sp, textAlign = TextAlign.End), color = RouteColors.OnSurface)
            }
        }
        ChartLegend(listOf(Triple("Montée", RouteColors.Climb, false), Triple("Descente", RouteColors.Descent, false)))
    }
}

/** Texte d'information sous un onglet sans données (pas de FC, pas d'altitude). */
@Composable
internal fun MissingData(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .fillMaxWidth()
            .background(RouteColors.Container, RoundedCornerShape(28.dp))
            .padding(20.dp),
        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        color = RouteColors.OnSurfaceVariant,
    )
}

/** Décélération accentuée de Material 3 (cubic-bezier(0.05, 0.7, 0.1, 1)). */
internal val EmphasizedDecelerate = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Ressort spatial de la maquette pour le plein écran (cubic-bezier(0.38, 1.21, 0.22, 1)). */
internal val ExpressiveSpatial = androidx.compose.animation.core.CubicBezierEasing(0.38f, 1.21f, 0.22f, 1f)
