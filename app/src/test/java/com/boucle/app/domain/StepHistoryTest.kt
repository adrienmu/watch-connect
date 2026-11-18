package com.boucle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class StepHistoryTest {

    private fun date(day: Int, month: Int = 11) = LocalDate.of(2026, month, day)

    @Test
    fun `objectifs par défaut avant tout réglage`() {
        assertEquals(DailyGoals(10_000, 600), GoalHistory().on(date(18)))
    }

    @Test
    fun `un changement ne vaut qu'à partir de son jour`() {
        val history = GoalHistory().with(DailyGoals(12_000, 500), date(18))
        assertEquals(10_000, history.on(date(17)).steps)
        assertEquals(12_000, history.on(date(18)).steps)
        assertEquals(500, history.on(date(30)).activeCalories)
    }

    @Test
    fun `un second réglage le même jour remplace le premier`() {
        val history = GoalHistory()
            .with(DailyGoals(12_000, 600), date(18))
            .with(DailyGoals(8_000, 600), date(18))
        assertEquals(listOf(GoalChange(date(18), DailyGoals(8_000, 600))), history.changes)
    }

    @Test
    fun `revenir à l'objectif précédent le même jour efface le changement`() {
        val history = GoalHistory()
            .with(DailyGoals(12_000, 600), date(18))
            .with(DailyGoals(), date(18))
        assertTrue(history.changes.isEmpty())
    }

    @Test
    fun `l'historique survit à l'encodage`() {
        val history = GoalHistory()
            .with(DailyGoals(12_000, 500), date(10))
            .with(DailyGoals(9_000, 450), date(18))
        assertEquals(history, GoalHistory.decode(history.encode()))
    }

    @Test
    fun `un texte illisible donne un historique vide`() {
        assertEquals(GoalHistory(), GoalHistory.decode(null))
        assertEquals(GoalHistory(), GoalHistory.decode("n'importe quoi"))
        assertEquals(1, GoalHistory.decode("2026-11-18:12000:500;cassé").changes.size)
    }

    @Test
    fun `la semaine va du lundi au dimanche`() {
        // Le 18 novembre 2026 est un mercredi.
        val week = StepHistory().week(date(18))
        assertEquals(date(16), week.first().date)
        assertEquals(date(22), week.last().date)
        assertEquals(7, week.size)
    }

    @Test
    fun `un jour est atteint selon l'objectif de ce jour-là`() {
        val history = StepHistory(
            steps = mapOf(date(17) to 10_500, date(18) to 10_500),
            goals = GoalHistory().with(DailyGoals(12_000, 600), date(18)),
        )
        assertTrue(history.day(date(17)).reached)
        assertFalse(history.day(date(18)).reached)
        assertFalse(history.day(date(19)).reached)
    }

    @Test
    fun `la série en cours compte jusqu'à la veille tant que le jour n'est pas atteint`() {
        val history = StepHistory(mapOf(date(15) to 11_000, date(16) to 10_000, date(17) to 12_000, date(18) to 4_000))
        assertEquals(3, history.currentStreak(date(18)))
    }

    @Test
    fun `la série en cours inclut le jour une fois l'objectif atteint`() {
        val history = StepHistory(mapOf(date(16) to 10_000, date(17) to 12_000, date(18) to 10_200))
        assertEquals(3, history.currentStreak(date(18)))
    }

    @Test
    fun `une veille manquée remet la série à zéro`() {
        val history = StepHistory(mapOf(date(16) to 12_000, date(17) to 3_000, date(18) to 4_000))
        assertEquals(0, history.currentStreak(date(18)))
    }

    @Test
    fun `bilan d'une période`() {
        val history = StepHistory(
            mapOf(date(16) to 11_000, date(17) to 12_000, date(18) to 4_000, date(20) to 10_000),
        )
        val stats = StepHistory.stats(history.week(date(18)))
        assertEquals(3, stats.reachedDays)
        assertEquals(4, stats.recordedDays)
        assertEquals(37_000, stats.total)
        assertEquals(9_250, stats.average)
        assertEquals(date(17), stats.best?.date)
        assertEquals(2, stats.longestStreak)
    }

    @Test
    fun `bilan d'une période sans mesure`() {
        val stats = StepHistory.stats(StepHistory().month(YearMonth.of(2026, 11)))
        assertEquals(0, stats.recordedDays)
        assertNull(stats.average)
        assertNull(stats.best)
    }
}
