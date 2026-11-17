package com.boucle.app.ui.screens

import com.boucle.app.domain.SleepBand
import com.boucle.app.ui.SleepStats

/**
 * Mise en forme des durées et du sommeil, partagée par les écrans Sommeil et Aujourd'hui.
 * Regroupée dans un objet pour ne pas entrer en conflit avec les aides des autres écrans du paquet.
 */
internal object SleepFormat {
    /** Espace insécable : « 7 h 46 » ne doit pas se couper en fin de ligne. */
    const val NBSP = ' '

    /** États du bracelet comptés comme du sommeil (l'éveil et l'inconnu sont exclus). */
    private val ASLEEP_STATES = setOf("REM", "NONREM12", "NONREM3")

    /** 1 380 s → « 23 min » ; 27 960 s → « 7 h 46 » ; 28 800 s → « 8 h ». */
    fun duration(seconds: Long): String {
        val minutes = (seconds + 30) / 60
        if (minutes < 60) return "$minutes${NBSP}min"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0L) "$hours${NBSP}h" else "$hours${NBSP}h$NBSP%02d".format(rest)
    }

    /** Temps réellement dormi : la somme des phases de sommeil, à défaut le temps passé au lit. */
    fun asleepSec(sleep: SleepStats): Long? =
        sleep.secondsByState.filterKeys { it in ASLEEP_STATES }.values.sum().takeIf { it > 0 } ?: sleep.totalSec

    /** Libellé de la catégorie du score de sommeil (« Bon »). */
    fun bandLabel(band: SleepBand): String = when (band) {
        SleepBand.EXCELLENT -> "Excellent"
        SleepBand.GOOD -> "Bon"
        SleepBand.FAIR -> "Moyen"
        SleepBand.POOR -> "Faible"
    }
}
