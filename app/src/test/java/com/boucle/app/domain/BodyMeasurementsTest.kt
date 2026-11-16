package com.boucle.app.domain

import com.boucle.app.data.db.ExternalMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BodyMeasurementsTest {

    private val today = LocalDate.of(2026, 11, 16)

    private fun m(type: MeasurementType, daysAgo: Long, value: Double, hour: Int = 7) = today.minusDays(daysAgo).let {
        ExternalMeasurementEntity("$type-$daysAgo-$hour", type.name, "${it}T0$hour:30:00Z", it.toString(), value, "com.withings.wiscale2")
    }

    @Test
    fun latestWeighInWithChangesAndComposition() {
        val data = listOf(
            m(MeasurementType.WEIGHT, 40, 76.0),
            m(MeasurementType.WEIGHT, 31, 75.5),
            m(MeasurementType.WEIGHT, 10, 74.8),
            m(MeasurementType.WEIGHT, 5, 74.6), // moins de 7 jours avant la pesée : pas une référence
            m(MeasurementType.WEIGHT, 2, 74.2),
            m(MeasurementType.BODY_FAT, 2, 18.4),
            m(MeasurementType.LEAN_MASS, 2, 60.5),
            m(MeasurementType.BONE_MASS, 20, 3.1), // trop ancienne pour accompagner la pesée
        )
        val body = BodyMeasurements.body(data, today)!!
        assertEquals(74.2, body.weightKg, 1e-9)
        assertEquals(today.minusDays(2), body.weighedOn)
        // Références : dernière pesée au moins 7 (resp. 30) jours avant la pesée affichée.
        assertEquals(74.2 - 74.8, body.change7Days!!, 1e-9)
        assertEquals(74.2 - 76.0, body.change30Days!!, 1e-9)
        assertEquals(18.4, body.bodyFatPct!!, 1e-9)
        assertEquals(60.5, body.leanMassKg!!, 1e-9)
        assertNull(body.boneMassKg)
        assertNull(body.waterMassKg)
    }

    @Test
    fun futureWeighInsAreIgnoredAndNoHistoryGivesNoChange() {
        val data = listOf(m(MeasurementType.WEIGHT, 3, 74.0), m(MeasurementType.WEIGHT, 0, 73.0))
        val body = BodyMeasurements.body(data, today.minusDays(1))!!
        assertEquals(74.0, body.weightKg, 1e-9)
        assertNull(body.change7Days)
        assertNull(BodyMeasurements.body(data, today.minusDays(5)))
        assertNull(BodyMeasurements.body(emptyList(), today))
    }

    @Test
    fun spo2OfTheDayIgnoresImplausibleValues() {
        val data = listOf(
            m(MeasurementType.SPO2, 0, 96.0, hour = 2),
            m(MeasurementType.SPO2, 0, 94.0, hour = 4),
            m(MeasurementType.SPO2, 0, 12.0, hour = 5),
            m(MeasurementType.SPO2, 1, 99.0, hour = 3),
        )
        val stats = BodyMeasurements.spo2(data, today)!!
        assertEquals(95.0, stats.average, 1e-9)
        assertEquals(94.0, stats.min, 1e-9)
        assertEquals(2, stats.count)
        assertNull(BodyMeasurements.spo2(data, today.minusDays(2)))
    }
}
