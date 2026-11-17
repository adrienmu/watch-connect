package com.boucle.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthDao {
    @Upsert suspend fun upsertDailyActivity(items: List<DailyActivityEntity>)
    @Upsert suspend fun upsertActivitySamples(items: List<ActivitySamplesEntity>)
    @Upsert suspend fun upsertHrBlocks(items: List<HrBlockEntity>)
    @Upsert suspend fun upsertPpiBlocks(items: List<PpiBlockEntity>)
    @Upsert suspend fun upsertSleep(items: List<SleepEntity>)
    @Upsert suspend fun upsertNightlyRecharge(items: List<NightlyRechargeEntity>)
    @Upsert suspend fun upsertSkinTemperature(items: List<SkinTemperatureEntity>)
    @Upsert suspend fun upsertTrainingSessions(items: List<TrainingSessionEntity>)
    @Upsert suspend fun upsertTrainingExercises(items: List<TrainingExerciseEntity>)
    @Upsert suspend fun upsertExternalMeasurements(items: List<ExternalMeasurementEntity>)
    @Upsert suspend fun upsertTrainingRoutes(items: List<TrainingRouteEntity>)

    @Query("SELECT * FROM training_route WHERE sessionPath = :path")
    fun trainingRoute(path: String): Flow<TrainingRouteEntity?>

    @Query("SELECT * FROM training_route WHERE sessionPath = :path")
    suspend fun trainingRouteNow(path: String): TrainingRouteEntity?

    @Query(
        "SELECT path, date, startTime, durationSec, sportId, name, calories, distanceMeters, " +
            "averageHr, maxHr, cardioLoad, muscleLoad, hrZoneSec, exerciseCount, NULL AS summaryProto " +
            "FROM training_session WHERE path = :path",
    )
    fun trainingSession(path: String): Flow<TrainingSessionEntity?>

    @Query("SELECT * FROM external_measurement WHERE date BETWEEN :from AND :to ORDER BY time")
    fun externalMeasurementsRange(from: String, to: String): Flow<List<ExternalMeasurementEntity>>

    @Query("SELECT * FROM external_measurement WHERE type = :type AND date BETWEEN :from AND :to ORDER BY time")
    fun externalMeasurementsOfType(type: String, from: String, to: String): Flow<List<ExternalMeasurementEntity>>

    @Query("SELECT * FROM daily_activity WHERE date = :date")
    fun dailyActivity(date: String): Flow<DailyActivityEntity?>

    @Query("SELECT * FROM daily_activity WHERE date BETWEEN :from AND :to ORDER BY date")
    fun dailyActivityRange(from: String, to: String): Flow<List<DailyActivityEntity>>

    @Query("SELECT * FROM activity_samples WHERE date = :date ORDER BY startTime")
    fun activitySamples(date: String): Flow<List<ActivitySamplesEntity>>

    @Query("SELECT * FROM hr_block WHERE date = :date ORDER BY startTime")
    fun hrBlocks(date: String): Flow<List<HrBlockEntity>>

    @Query("SELECT * FROM ppi_block WHERE date = :date ORDER BY startTime")
    fun ppiBlocks(date: String): Flow<List<PpiBlockEntity>>

    @Query("SELECT * FROM sleep WHERE date = :date")
    fun sleep(date: String): Flow<SleepEntity?>

    @Query("SELECT * FROM activity_samples WHERE date BETWEEN :from AND :to ORDER BY date, startTime")
    fun activitySamplesRange(from: String, to: String): Flow<List<ActivitySamplesEntity>>

    @Query("SELECT * FROM hr_block WHERE date BETWEEN :from AND :to ORDER BY date, startTime")
    fun hrBlocksRange(from: String, to: String): Flow<List<HrBlockEntity>>

    @Query("SELECT * FROM ppi_block WHERE date BETWEEN :from AND :to ORDER BY date, startTime")
    fun ppiBlocksRange(from: String, to: String): Flow<List<PpiBlockEntity>>

    @Query("SELECT * FROM sleep WHERE date BETWEEN :from AND :to ORDER BY date")
    fun sleepRange(from: String, to: String): Flow<List<SleepEntity>>

    @Query("SELECT * FROM nightly_recharge WHERE date BETWEEN :from AND :to ORDER BY date")
    fun nightlyRechargeRange(from: String, to: String): Flow<List<NightlyRechargeEntity>>

    @Query("SELECT * FROM nightly_recharge WHERE date = :date")
    fun nightlyRecharge(date: String): Flow<NightlyRechargeEntity?>

    @Query("SELECT * FROM skin_temperature WHERE date = :date")
    fun skinTemperature(date: String): Flow<SkinTemperatureEntity?>

    @Query(
        "SELECT path, date, startTime, durationSec, sportId, name, calories, distanceMeters, " +
            "averageHr, maxHr, cardioLoad, muscleLoad, hrZoneSec, exerciseCount, NULL AS summaryProto " +
            "FROM training_session WHERE date = :date ORDER BY startTime",
    )
    fun trainingSessions(date: String): Flow<List<TrainingSessionEntity>>

    @Query(
        "SELECT path, date, startTime, durationSec, sportId, name, calories, distanceMeters, " +
            "averageHr, maxHr, cardioLoad, muscleLoad, hrZoneSec, exerciseCount, NULL AS summaryProto " +
            "FROM training_session WHERE date BETWEEN :from AND :to ORDER BY date, startTime",
    )
    fun trainingSessionsRange(from: String, to: String): Flow<List<TrainingSessionEntity>>
}
