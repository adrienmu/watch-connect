package com.boucle.app.data

import com.boucle.app.data.db.ActivitySamplesEntity
import com.boucle.app.data.db.DailyActivityEntity
import com.boucle.app.data.db.HealthDao
import com.boucle.app.data.db.HrBlockEntity
import com.boucle.app.data.db.NightlyRechargeEntity
import com.boucle.app.data.db.PpiBlockEntity
import com.boucle.app.data.db.SkinTemperatureEntity
import com.boucle.app.data.db.SleepEntity
import com.boucle.app.data.db.TrainingSessionEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Données fictives mais plausibles, écrites dans les mêmes tables que la synchronisation réelle,
 * pour développer et tester les écrans sans bracelet. Graine fixe : les mêmes jours donnent
 * toujours les mêmes valeurs.
 */
object DemoData {
    private val zone = ZoneId.of("Europe/Paris")

    /** Profil fictif utilisé par la démo quand aucun vrai profil n'est enregistré. */
    val profile = UserProfile(
        female = false,
        birthDate = LocalDate.of(1990, 6, 15),
        heightCm = 178f,
        weightKg = 74f,
        maxHr = null,
    )

    suspend fun load(dao: HealthDao, days: Int = 14, today: LocalDate = LocalDate.now()) {
        val rnd = Random(7)
        for (offset in days - 1 downTo 0) {
            val date = today.minusDays(offset.toLong())
            val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            val trained = rnd.nextInt(100) < 45

            val sleep = buildSleep(date, rnd)
            dao.upsertSleep(listOf(sleep.entity))
            dao.upsertNightlyRecharge(listOf(recharge(date, sleep.totalSec, rnd)))
            dao.upsertSkinTemperature(listOf(skinTemperature(date, rnd)))
            dao.upsertPpiBlocks(ppiBlocks(date, rnd))
            dao.upsertHrBlocks(hrBlocks(date, trained, rnd))

            val minuteSteps = minuteSteps(weekend, trained, rnd)
            val steps = minuteSteps.sum()
            dao.upsertActivitySamples(listOf(activitySamples(date, minuteSteps)))
            dao.upsertDailyActivity(listOf(dailyActivity(date, steps, trained, rnd)))

            if (trained) dao.upsertTrainingSessions(listOf(trainingSession(date, rnd)))
        }
    }

    private fun Float.oneDecimal(): String = ((this * 10).roundToInt() / 10f).toString()

    private fun minuteSteps(weekend: Boolean, trained: Boolean, rnd: Random): List<Int> {
        val target = ((if (weekend) 9_000 else 7_500) + rnd.nextInt(-3_000, 5_000) + if (trained) 3_000 else 0)
            .coerceAtLeast(2_000)
        val raw = IntArray(1440) { minute ->
            // Pas de pas la nuit ; le jour, des marches par à-coups.
            if (minute < 420 || minute >= 1400) 0 else if (rnd.nextInt(100) < 35) rnd.nextInt(20, 110) else 0
        }
        val factor = target.toFloat() / raw.sum().coerceAtLeast(1)
        return raw.map { (it * factor).roundToInt() }
    }

    private fun activitySamples(date: LocalDate, minuteSteps: List<Int>): ActivitySamplesEntity {
        val met = minuteSteps.flatMapIndexed { minute, steps ->
            val value = if (minute < 420 || minute >= 1400) 0.9f else 1.0f + steps / 35f
            listOf(value, value)
        }
        return ActivitySamplesEntity(
            date = date.toString(),
            startTime = "${date}T00:00",
            stepIntervalSec = 60,
            stepSamples = minuteSteps.joinToString(","),
            metIntervalSec = 30,
            metSamples = met.joinToString(",") { it.oneDecimal() },
            activityInfo = "",
        )
    }

    private fun dailyActivity(date: LocalDate, steps: Int, trained: Boolean, rnd: Random): DailyActivityEntity {
        val moderateMin = 25 + rnd.nextInt(0, 30) + if (trained) 20 else 0
        val vigorousMin = if (trained) 10 + rnd.nextInt(0, 25) else rnd.nextInt(0, 6)
        val goal = 100f
        return DailyActivityEntity(
            date = date.toString(),
            steps = steps,
            activityCalories = 280 + (steps * 0.04f).roundToInt() + rnd.nextInt(0, 60),
            trainingCalories = if (trained) 300 + rnd.nextInt(0, 350) else 0,
            bmrCalories = 1_650 + rnd.nextInt(-30, 30),
            distanceMeters = steps * 0.74f,
            activityGoal = goal,
            achievedActivity = (steps / 9_000f * goal).coerceAtMost(130f),
            sedentarySec = (8 * 3600) + rnd.nextInt(0, 7_200),
            lightSec = (3 * 3600) + rnd.nextInt(0, 3_600),
            moderateSec = moderateMin * 60,
            vigorousSec = vigorousMin * 60,
            sleepSec = 7 * 3600 + rnd.nextInt(0, 5_400),
            nonWearSec = 0,
            dailyBalance = "RESPONDING_WELL_CAN_CONTINUE",
            readiness = "RECOVERED_READY_FOR_ALL_TRAINING",
        )
    }

    private fun hrBlocks(date: LocalDate, trained: Boolean, rnd: Random): List<HrBlockEntity> =
        (0..23).map { hour ->
            val base = when {
                hour <= 6 -> 54
                hour >= 22 -> 58
                trained && hour == 18 -> 135
                else -> 72
            }
            val spread = if (hour in 7..21) 10 else 5
            HrBlockEntity(
                date = date.toString(),
                startTime = "%02d:00".format(hour),
                trigger = "TRIGGER_TYPE_LOW_ACTIVITY",
                samples = (1..12).joinToString(",") { (base + rnd.nextInt(-spread, spread + 1)).toString() },
            )
        }

    private fun ppiBlocks(date: LocalDate, rnd: Random): List<PpiBlockEntity> =
        listOf(2, 3, 4, 5).map { hour ->
            val intervals = List(60) { 1_100 + rnd.nextInt(-60, 61) }
            PpiBlockEntity(
                date = date.toString(),
                startTime = "%02d:00".format(hour),
                trigger = "TRIGGER_TYPE_AUTOMATIC",
                ppiMs = intervals.joinToString(","),
                errorEstimateMs = intervals.joinToString(",") { "8" },
            )
        }

    private fun skinTemperature(date: LocalDate, rnd: Random): SkinTemperatureEntity {
        val base = 33.5f + rnd.nextInt(-5, 6) / 10f
        return SkinTemperatureEntity(
            date = date.toString(),
            sensorLocation = "SL_DISTAL",
            measurementType = "TM_SKIN_TEMPERATURE",
            // Un point toutes les 5 minutes pendant 8 h.
            samples = (0 until 96).joinToString(",") { index ->
                "${index * 300_000L}:${(base + rnd.nextInt(-3, 4) / 10f).oneDecimal()}"
            },
        )
    }

    private fun recharge(date: LocalDate, sleepSec: Long, rnd: Random): NightlyRechargeEntity {
        val quality = (sleepSec / 3600f / 8f).coerceIn(0.6f, 1.15f)
        val rmssd = (28 + 28 * quality + rnd.nextInt(-8, 9)).roundToInt()
        val ansStatus = ((quality - 0.9f) * 6f + rnd.nextInt(-10, 11) / 10f).coerceIn(-3f, 3f)
        return NightlyRechargeEntity(
            date = date.toString(),
            ansStatus = ansStatus,
            recoveryIndicator = (3 + ansStatus.roundToInt() / 2).coerceIn(1, 5),
            recoveryIndicatorSubLevel = rnd.nextInt(0, 3),
            ansRate = (50 + ansStatus * 10).roundToInt().coerceIn(1, 100),
            meanRriMs = 1_120 + rnd.nextInt(-60, 61),
            meanRmssdMs = rmssd,
            meanRespirationIntervalMs = 4_200 + rnd.nextInt(-300, 301),
            baselineRriMs = 1_100,
            baselineRriSd = 70,
            baselineRmssdMs = 52,
            baselineRmssdSd = 11,
            baselineRespirationIntervalMs = 4_250,
            baselineRespirationIntervalSd = 250,
            sleepTip = null,
            vitalityTip = null,
            exerciseTip = null,
        )
    }

    private class NightSleep(val entity: SleepEntity, val totalSec: Long)

    private fun buildSleep(date: LocalDate, rnd: Random): NightSleep {
        val start: ZonedDateTime = date.minusDays(1).atTime(23, 0).plusMinutes(rnd.nextInt(-45, 76).toLong()).atZone(zone)
        val totalSec = (6.3f * 3600).toLong() + rnd.nextInt(0, (2.3f * 3600).toInt())
        val end = start.plusSeconds(totalSec)

        val phases = mutableListOf(0 to "WAKE")
        val cycles = mutableListOf<Pair<Int, Float>>()
        var t = rnd.nextInt(300, 900)
        var cycle = 0
        while (t < totalSec) {
            cycles += t to rnd.nextInt(5, 30) / 10f
            val deepMin = maxOf(0, 35 - cycle * 12)
            val remMin = 5 + cycle * 7
            for ((state, minutes) in listOf(
                "NONREM12" to 20,
                "NONREM3" to deepMin,
                "NONREM12" to 15,
                "REM" to remMin,
                "WAKE" to if (rnd.nextInt(100) < 40) rnd.nextInt(2, 6) else 0,
            )) {
                if (minutes > 0 && t < totalSec) {
                    phases += t to state
                    t += minutes * 60
                }
            }
            cycle++
        }

        // Fusionne les phases consécutives identiques.
        val merged = phases.fold(mutableListOf<Pair<Int, String>>()) { acc, phase ->
            if (acc.lastOrNull()?.second != phase.second) acc += phase
            acc
        }

        return NightSleep(
            SleepEntity(
                date = date.toString(),
                startTime = start.toString(),
                endTime = end.toString(),
                goalMinutes = 480,
                phases = merged.joinToString(",") { "${it.first}:${it.second}" },
                cycles = cycles.joinToString(",") { "${it.first}:${it.second}" },
                userRating = null,
                batteryRanOut = false,
                skinTemperatureC = 33.5f + rnd.nextInt(-5, 6) / 10f,
                skinTemperatureDeviationC = rnd.nextInt(-6, 7) / 10f,
            ),
            totalSec,
        )
    }

    private fun trainingSession(date: LocalDate, rnd: Random): TrainingSessionEntity {
        val (name, sportId) = listOf("Course à pied" to 1L, "Vélo" to 2L, "Renforcement" to 15L).random(rnd)
        val durationSec = (30 + rnd.nextInt(0, 45)) * 60
        val start = date.atTime(18, rnd.nextInt(0, 45), 0)
        val zones = listOf(0.05f, 0.2f, 0.4f, 0.25f, 0.1f).map { (it * durationSec).roundToInt() }
        return TrainingSessionEntity(
            path = "/U/0/${date.toString().replace("-", "")}/E/${"%02d%02d00".format(start.hour, start.minute)}/",
            date = date.toString(),
            startTime = start.toString(),
            durationSec = durationSec,
            sportId = sportId,
            name = name,
            calories = durationSec / 60 * 9,
            distanceMeters = if (sportId == 15L) 0f else durationSec / 60 * 160f,
            averageHr = 138 + rnd.nextInt(-8, 9),
            maxHr = 168 + rnd.nextInt(-6, 7),
            cardioLoad = durationSec / 60 * 1.4f,
            muscleLoad = null,
            hrZoneSec = zones.joinToString(","),
            exerciseCount = 1,
            summaryProto = null,
        )
    }
}
