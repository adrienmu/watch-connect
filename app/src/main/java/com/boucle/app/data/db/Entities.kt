package com.boucle.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/*
 * Stockage brut de tout ce que le bracelet expose. Les dates sont des chaînes ISO
 * (yyyy-MM-dd) pour rester triables, les séries sont des listes séparées par des
 * virgules : les scores seront calculés plus tard à partir de ces tables.
 */

@Entity(tableName = "daily_activity")
data class DailyActivityEntity(
    @PrimaryKey val date: String,
    val steps: Int?,
    val activityCalories: Int?,
    val trainingCalories: Int?,
    val bmrCalories: Int?,
    val distanceMeters: Float?,
    val activityGoal: Float?,
    val achievedActivity: Float?,
    val sedentarySec: Int?,
    val lightSec: Int?,
    val moderateSec: Int?,
    val vigorousSec: Int?,
    val sleepSec: Int?,
    val nonWearSec: Int?,
    val dailyBalance: String?,
    val readiness: String?,
)

/** Pas à la minute et MET toutes les 30 s, par bloc d'enregistrement. */
@Entity(tableName = "activity_samples", primaryKeys = ["date", "startTime"])
data class ActivitySamplesEntity(
    val date: String,
    val startTime: String,
    val stepIntervalSec: Int?,
    val stepSamples: String,
    val metIntervalSec: Int?,
    val metSamples: String,
    /** Éléments « horodatage|classe|facteur » séparés par des points-virgules. */
    val activityInfo: String,
)

@Entity(tableName = "hr_block", primaryKeys = ["date", "startTime"])
data class HrBlockEntity(
    val date: String,
    val startTime: String,
    val trigger: String,
    val samples: String,
)

@Entity(tableName = "ppi_block", primaryKeys = ["date", "startTime"])
data class PpiBlockEntity(
    val date: String,
    val startTime: String,
    val trigger: String,
    val ppiMs: String,
    val errorEstimateMs: String,
)

@Entity(tableName = "sleep")
data class SleepEntity(
    @PrimaryKey val date: String,
    val startTime: String?,
    val endTime: String?,
    val goalMinutes: Int?,
    /** Éléments « secondesDepuisLeDébut:ÉTAT » séparés par des virgules. */
    val phases: String,
    /** Éléments « secondesDepuisLeDébut:profondeur » séparés par des virgules. */
    val cycles: String,
    val userRating: String?,
    val batteryRanOut: Boolean?,
    val skinTemperatureC: Float?,
    val skinTemperatureDeviationC: Float?,
)

@Entity(tableName = "nightly_recharge")
data class NightlyRechargeEntity(
    @PrimaryKey val date: String,
    val ansStatus: Float?,
    val recoveryIndicator: Int?,
    val recoveryIndicatorSubLevel: Int?,
    val ansRate: Int?,
    val meanRriMs: Int?,
    val meanRmssdMs: Int?,
    val meanRespirationIntervalMs: Int?,
    val baselineRriMs: Int?,
    val baselineRriSd: Int?,
    val baselineRmssdMs: Int?,
    val baselineRmssdSd: Int?,
    val baselineRespirationIntervalMs: Int?,
    val baselineRespirationIntervalSd: Int?,
    val sleepTip: String?,
    val vitalityTip: String?,
    val exerciseTip: String?,
)

@Entity(tableName = "skin_temperature")
data class SkinTemperatureEntity(
    @PrimaryKey val date: String,
    val sensorLocation: String?,
    val measurementType: String?,
    /** Éléments « deltaMs:températureC » séparés par des virgules. */
    val samples: String,
)

@Entity(tableName = "training_session")
data class TrainingSessionEntity(
    @PrimaryKey val path: String,
    val date: String,
    val startTime: String?,
    val durationSec: Int?,
    val sportId: Long?,
    val name: String?,
    val calories: Int?,
    val distanceMeters: Float?,
    val averageHr: Int?,
    val maxHr: Int?,
    val cardioLoad: Float?,
    val muscleLoad: Float?,
    /** Durée en secondes par zone de FC, séparées par des virgules. */
    val hrZoneSec: String,
    val exerciseCount: Int,
    /** Message protobuf d'origine, gardé pour pouvoir en extraire plus de champs plus tard. */
    val summaryProto: ByteArray?,
)

/** Échantillons bruts (FC, etc.) d'un exercice, au format protobuf du bracelet. */
@Entity(tableName = "training_exercise", primaryKeys = ["sessionPath", "exerciseIndex"])
data class TrainingExerciseEntity(
    val sessionPath: String,
    val exerciseIndex: Int,
    val baseProto: ByteArray?,
    val samplesProto: ByteArray?,
)
