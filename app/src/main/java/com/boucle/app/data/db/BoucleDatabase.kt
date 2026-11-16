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
    ],
    version = 2,
    // v2 : table des mesures Health Connect ; les données du bracelet sont conservées.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class BoucleDatabase : RoomDatabase() {
    abstract fun healthDao(): HealthDao

    companion object {
        fun create(context: Context): BoucleDatabase =
            Room.databaseBuilder(context, BoucleDatabase::class.java, "boucle.db").build()
    }
}
