package com.boucle.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Pas d'un jour (yyyy-MM-dd), lus seuls pour l'historique. */
data class DateSteps(val date: String, val steps: Int)

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
    @Upsert suspend fun upsertActivityEdits(items: List<ActivityEditEntity>)

    // Import d'une sauvegarde : seules les lignes dont la clé est absente sont ajoutées, une ligne déjà présente
    // (plus récente, synchronisée depuis la sauvegarde) n'est jamais remplacée. Le résultat donne, pour chaque
    // ligne, son rowid, ou -1 si elle a été ignorée parce qu'elle existait déjà.

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDailyActivityIfAbsent(items: List<DailyActivityEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActivitySamplesIfAbsent(items: List<ActivitySamplesEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHrBlocksIfAbsent(items: List<HrBlockEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPpiBlocksIfAbsent(items: List<PpiBlockEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSleepIfAbsent(items: List<SleepEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNightlyRechargeIfAbsent(items: List<NightlyRechargeEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSkinTemperatureIfAbsent(items: List<SkinTemperatureEntity>): List<Long>

    /** Une séance à la fois : ses exercices et son tracé ne sont importés que si elle vient d'être ajoutée. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrainingSessionIfAbsent(item: TrainingSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrainingExercisesIfAbsent(items: List<TrainingExerciseEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrainingRoutesIfAbsent(items: List<TrainingRouteEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExternalMeasurementsIfAbsent(items: List<ExternalMeasurementEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActivityEditsIfAbsent(items: List<ActivityEditEntity>): List<Long>

    @Query("SELECT * FROM activity_edit")
    fun activityEdits(): Flow<List<ActivityEditEntity>>

    @Query("SELECT * FROM activity_edit WHERE sessionPath = :path")
    suspend fun activityEditNow(path: String): ActivityEditEntity?

    @Query("SELECT * FROM activity_edit ORDER BY sessionPath")
    suspend fun allActivityEdits(): List<ActivityEditEntity>

    /** Dernières séances, les plus récentes d'abord (les séances supprimées sont écartées par l'appelant). */
    @Query(
        "SELECT path, date, startTime, durationSec, sportId, name, calories, distanceMeters, " +
            "averageHr, maxHr, cardioLoad, muscleLoad, hrZoneSec, exerciseCount, NULL AS summaryProto " +
            "FROM training_session ORDER BY date DESC, startTime DESC LIMIT :limit",
    )
    fun recentTrainingSessions(limit: Int): Flow<List<TrainingSessionEntity>>

    @Query("SELECT sessionPath FROM training_route WHERE status = 'AVAILABLE'")
    fun routedSessionPaths(): Flow<List<String>>

    /** Tous les tracés lisibles, pour la carte d'exploration. */
    @Query("SELECT * FROM training_route WHERE status = 'AVAILABLE'")
    fun availableTrainingRoutes(): Flow<List<TrainingRouteEntity>>

    @Query("DELETE FROM activity_edit WHERE sessionPath = :path")
    suspend fun deleteActivityEdit(path: String)

    @Query("DELETE FROM training_session WHERE path = :path")
    suspend fun deleteTrainingSession(path: String)

    @Query("DELETE FROM training_route WHERE sessionPath = :path")
    suspend fun deleteTrainingRoute(path: String)

    @Query("DELETE FROM training_exercise WHERE sessionPath = :path")
    suspend fun deleteTrainingExercises(path: String)

    /** Au plus quelques centaines d'identifiants par appel (limite de SQLite sur les paramètres). */
    @Query("DELETE FROM external_measurement WHERE id IN (:ids)")
    suspend fun deleteExternalMeasurements(ids: List<String>)

    @Query("SELECT EXISTS(SELECT 1 FROM training_session WHERE path = :path)")
    suspend fun hasTrainingSession(path: String): Boolean

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

    /** Pas de chaque jour mesuré, pour l'historique (semaines, mois, séries). */
    @Query("SELECT date, steps FROM daily_activity WHERE steps IS NOT NULL ORDER BY date")
    fun dailySteps(): Flow<List<DateSteps>>

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

    /** Vrai si la base contient au moins une donnée : une sauvegarde automatique d'une base vide n'a pas de sens. */
    @Query(
        "SELECT " +
            "EXISTS(SELECT 1 FROM daily_activity) OR " +
            "EXISTS(SELECT 1 FROM activity_samples) OR " +
            "EXISTS(SELECT 1 FROM hr_block) OR " +
            "EXISTS(SELECT 1 FROM ppi_block) OR " +
            "EXISTS(SELECT 1 FROM sleep) OR " +
            "EXISTS(SELECT 1 FROM nightly_recharge) OR " +
            "EXISTS(SELECT 1 FROM skin_temperature) OR " +
            "EXISTS(SELECT 1 FROM training_session) OR " +
            "EXISTS(SELECT 1 FROM training_exercise) OR " +
            "EXISTS(SELECT 1 FROM external_measurement) OR " +
            "EXISTS(SELECT 1 FROM training_route) OR " +
            "EXISTS(SELECT 1 FROM activity_edit)",
    )
    suspend fun hasAnyData(): Boolean

    // Export de toutes les données (droit à la portabilité). Les séries volumineuses se lisent jour par jour.

    @Query("SELECT * FROM daily_activity ORDER BY date")
    suspend fun allDailyActivity(): List<DailyActivityEntity>

    @Query("SELECT * FROM sleep ORDER BY date")
    suspend fun allSleep(): List<SleepEntity>

    @Query("SELECT * FROM nightly_recharge ORDER BY date")
    suspend fun allNightlyRecharge(): List<NightlyRechargeEntity>

    @Query("SELECT * FROM skin_temperature ORDER BY date")
    suspend fun allSkinTemperature(): List<SkinTemperatureEntity>

    @Query("SELECT * FROM external_measurement ORDER BY time")
    suspend fun allExternalMeasurements(): List<ExternalMeasurementEntity>

    @Query("SELECT * FROM training_session ORDER BY date, startTime")
    suspend fun allTrainingSessions(): List<TrainingSessionEntity>

    @Query("SELECT * FROM training_route")
    suspend fun allTrainingRoutes(): List<TrainingRouteEntity>

    @Query("SELECT * FROM training_exercise WHERE sessionPath = :path ORDER BY exerciseIndex")
    suspend fun trainingExercisesOf(path: String): List<TrainingExerciseEntity>

    /** Exercices dont la séance n'est pas (ou plus) dans la base : exportés à part pour ne rien perdre. */
    @Query(
        "SELECT * FROM training_exercise WHERE sessionPath NOT IN (SELECT path FROM training_session) " +
            "ORDER BY sessionPath, exerciseIndex",
    )
    suspend fun unlinkedTrainingExercises(): List<TrainingExerciseEntity>

    @Query("SELECT DISTINCT date FROM activity_samples ORDER BY date")
    suspend fun activitySampleDates(): List<String>

    @Query("SELECT * FROM activity_samples WHERE date = :date ORDER BY startTime")
    suspend fun activitySamplesOn(date: String): List<ActivitySamplesEntity>

    @Query("SELECT DISTINCT date FROM hr_block ORDER BY date")
    suspend fun hrBlockDates(): List<String>

    @Query("SELECT * FROM hr_block WHERE date = :date ORDER BY startTime")
    suspend fun hrBlocksOn(date: String): List<HrBlockEntity>

    @Query("SELECT DISTINCT date FROM ppi_block ORDER BY date")
    suspend fun ppiBlockDates(): List<String>

    @Query("SELECT * FROM ppi_block WHERE date = :date ORDER BY startTime")
    suspend fun ppiBlocksOn(date: String): List<PpiBlockEntity>
}
