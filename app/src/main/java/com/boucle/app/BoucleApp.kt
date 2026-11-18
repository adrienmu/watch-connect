package com.boucle.app

import android.app.Application
import com.boucle.app.data.Settings
import com.boucle.app.data.backup.AutoBackup
import com.boucle.app.data.backup.BackupKeyStore
import com.boucle.app.data.db.BoucleDatabase
import com.boucle.app.healthconnect.HealthConnectGateway
import com.boucle.app.polar.PolarGateway
import com.boucle.app.polar.SyncEngine
import com.boucle.app.tracking.ActivityRecorder

class BoucleApp : Application() {
    val settings by lazy { Settings(this) }
    val database by lazy { BoucleDatabase.create(this) }
    val gateway by lazy { PolarGateway(this) }
    val syncEngine by lazy { SyncEngine(gateway, database.healthDao()) }
    val healthConnect by lazy { HealthConnectGateway(this) }
    val backupKeys by lazy { BackupKeyStore(settings) }
    /** Enregistrement d'une activité avec le GPS du téléphone ; la FC vient du bracelet connecté. */
    val recorder by lazy { ActivityRecorder(this) { gateway.state.value.liveHr } }
    val autoBackup by lazy { AutoBackup(this, settings, database.healthDao(), backupKeys) }
}
