package com.boucle.app.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.boucle.app.BoucleApp

/**
 * Travail WorkManager de la sauvegarde automatique (après une synchro, quotidien, ou « Sauvegarder maintenant »).
 * Pas de notification : le résultat est enregistré dans les réglages et affiché dans Appareils.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BoucleApp
        // Un échec est déjà enregistré pour l'écran ; réessayer en boucle n'aiderait pas (dossier perdu, plein…) :
        // la sauvegarde quotidienne ou la prochaine synchro retenteront.
        return when (app.autoBackup.run(force = inputData.getBoolean(KEY_FORCE, false))) {
            is BackupOutcome.Failed -> Result.failure()
            else -> Result.success()
        }
    }

    internal companion object {
        const val KEY_FORCE = "force"
    }
}
