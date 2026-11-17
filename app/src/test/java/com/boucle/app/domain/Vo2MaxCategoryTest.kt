package com.boucle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class Vo2MaxCategoryTest {

    @Test
    fun thresholdsAreInclusiveLowerBounds() {
        // Homme de 35 ans : Moyen dès 38, Bon dès 43, Excellent dès 50.
        assertEquals(Vo2MaxCategory.POOR, Vo2MaxEstimation.category(37, age = 35, female = false))
        assertEquals(Vo2MaxCategory.FAIR, Vo2MaxEstimation.category(38, age = 35, female = false))
        assertEquals(Vo2MaxCategory.FAIR, Vo2MaxEstimation.category(42, age = 35, female = false))
        assertEquals(Vo2MaxCategory.GOOD, Vo2MaxEstimation.category(43, age = 35, female = false))
        assertEquals(Vo2MaxCategory.GOOD, Vo2MaxEstimation.category(49, age = 35, female = false))
        assertEquals(Vo2MaxCategory.EXCELLENT, Vo2MaxEstimation.category(50, age = 35, female = false))
    }

    @Test
    fun sameValueRanksHigherWithAge() {
        assertEquals(Vo2MaxCategory.FAIR, Vo2MaxEstimation.category(42, age = 25, female = false))
        assertEquals(Vo2MaxCategory.GOOD, Vo2MaxEstimation.category(42, age = 45, female = false))
        assertEquals(Vo2MaxCategory.EXCELLENT, Vo2MaxEstimation.category(42, age = 65, female = false))
    }

    @Test
    fun womenHaveLowerThresholds() {
        assertEquals(Vo2MaxCategory.FAIR, Vo2MaxEstimation.category(40, age = 30, female = false))
        assertEquals(Vo2MaxCategory.GOOD, Vo2MaxEstimation.category(40, age = 30, female = true))
    }

    @Test
    fun agesOutsideTheTableUseTheNearestBand() {
        assertEquals(Vo2MaxEstimation.category(44, age = 25, female = true), Vo2MaxEstimation.category(44, age = 16, female = true))
        assertEquals(Vo2MaxEstimation.category(30, age = 65, female = true), Vo2MaxEstimation.category(30, age = 85, female = true))
    }

    @Test
    fun estimateCarriesTheCategoryOfItsValue() {
        val estimate = Vo2MaxEstimation.estimate(age = 40, female = true, profileMaxHr = 180, restingHr = 60.0, sessions = emptyList())!!
        // Uth : 14,5 × 180 / 60 = 43,5 → 44, au-dessus du seuil « Excellent » (39) d'une femme de 40 ans.
        assertEquals(44, estimate.value)
        assertEquals(Vo2MaxCategory.EXCELLENT, estimate.category)
    }
}
