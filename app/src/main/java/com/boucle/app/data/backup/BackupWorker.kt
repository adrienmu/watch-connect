package com.boucle.app.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import com.boucle.app.BoucleApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Travail WorkManager de la sauvegarde automatique (après une synchro, quotidien, ou « Sauvegarder maintenant »).
 * Pas de notification : le résultat est enregistré dans les réglages et affiché dans Appareils.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BoucleApp
        return try {
            // Un échec est déjà enregistré pour l'écran ; réessayer en boucle n'aiderait pas (dossier perdu, plein…) :
            // la sauvegarde quotidienne ou la prochaine synchro retenteront.
            when (app.autoBackup.run(force = inputData.getBoolean(KEY_FORCE, false))) {
                is BackupOutcome.Failed -> Result.failure()
                else -> Result.success()
            }
        } catch (e: CancellationException) {
            // Arrêtée par WorkManager (onStopped) : sans trace, l'écran afficherait encore la dernière réussite.
            backupStopMessage(stopReason)?.let { message ->
                withContext(NonCancellable) { runCatching { app.autoBackup.recordInterrupted(message) } }
            }
            throw e
        }
    }

    internal companion object {
        const val KEY_FORCE = "force"
    }
}

/**
 * Message d'échec d'une sauvegarde arrêtée par WorkManager avec [stopReason] ([WorkInfo.STOP_REASON_CANCELLED_BY_APP]
 * et autres), ou `null` quand l'arrêt vient de l'app elle-même : une nouvelle demande qui remplace celle-ci (REPLACE)
 * ou la désactivation de la sauvegarde. Ce n'est alors pas un échec.
 */
internal fun backupStopMessage(stopReason: Int): String? {
    val cause = when (stopReason) {
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> return null
        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "batterie faible"
        WorkInfo.STOP_REASON_TIMEOUT, WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "temps d'exécution dépassé"
        WorkInfo.STOP_REASON_USER -> "application arrêtée"
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "stockage presque plein"
        WorkInfo.STOP_REASON_APP_STANDBY, WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION, WorkInfo.STOP_REASON_QUOTA ->
            "activité en arrière-plan limitée par Android"
        else -> "arrêtée par Android"
    }
    return "sauvegarde interrompue ($cause), rien n'a été gardé ; elle sera retentée"
}
