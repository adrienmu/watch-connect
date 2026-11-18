package com.boucle.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DailyActivityEntity::class,
        ActivitySamplesEntity::class,
        HrBlockEntity::class,
        PpiBlockEntity::class,
        SleepEntity::class,
        NightlyRechargeEntity::class,
        SkinTemperatureEntity::class,
        TrainingSessionEntity::class,
        TrainingExerciseEntity::class,
        ExternalMeasurementEntity::class,
        TrainingRouteEntity::class,
        ActivityEditEntity::class,
    ],
    version = 4,
    // v2 : table des mesures Health Connect ; les données du bracelet sont conservées.
    // v3 : tracés GPS des séances.
    // v4 : corrections des séances (nom, sport, terrain, rognage, suppression).
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class BoucleDatabase : RoomDatabase() {
    abstract fun healthDao(): HealthDao

    companion object {
        fun create(context: Context): BoucleDatabase =
            Room.databaseBuilder(context, BoucleDatabase::class.java, "boucle.db").build()
    }
}
