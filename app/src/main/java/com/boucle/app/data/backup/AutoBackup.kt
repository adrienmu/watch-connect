package com.boucle.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.boucle.app.data.AppSettings
import com.boucle.app.data.Settings
import com.boucle.app.data.db.HealthDao
import com.boucle.app.data.export.DataExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Résultat d'une tentative de sauvegarde automatique. */
sealed interface BackupOutcome {
    data class Done(val fileName: String) : BackupOutcome
    data class Skipped(val reason: BackupDecision) : BackupOutcome
    data class Failed(val message: String) : BackupOutcome
}

/**
 * Sauvegarde automatique, sans Google ni serveur : l'archive de l'export ([DataExporter], importable telle quelle)
 * est écrite dans un dossier choisi par l'utilisateur (stockage du téléphone, carte SD, clé USB, dossier
 * synchronisé par une autre app…), après chaque synchronisation et une fois par jour.
 *
 * Chiffrement facultatif par phrase de passe ([BackupCrypto], clé gardée par [BackupKeyStore]) : l'archive est alors
 * chiffrée en flux pendant l'écriture, sans aucun fichier temporaire en clair, et nommée `….boucle` au lieu de
 * `….zip`. Si le chiffrement est activé mais que la clé est indisponible (transfert vers un autre téléphone), la
 * sauvegarde échoue avec un message clair : elle n'est jamais écrite en clair à la place.
 *
 * Écriture : `boucle-sauvegarde-AAAAMMJJ-HHMMSS.zip.partial` (ou `.boucle.partial`), renommé une fois complet ; si
 * le fournisseur du dossier ne sait pas renommer, écriture directe sous le nom final, supprimé en cas d'échec. Une
 * sauvegarde à moitié écrite ne porte donc jamais le nom d'une vraie. Puis rotation : seules les [BackupNaming.KEEP]
 * dernières (chiffrées ou non) sont gardées, et seuls les fichiers au motif exact de l'app sont supprimés.
 */
class AutoBackup(
    private val context: Context,
    private val settings: Settings,
    private val dao: HealthDao,
    private val keys: BackupKeyStore,
) {
    private val exporter = DataExporter(dao)
    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Écrit l'archive de sauvegarde dans [output], sans le fermer, chiffrée avec [key] si elle est fournie. C'est le
     * seul endroit où le contenu est produit : en clair, il ne passe que par la mémoire.
     */
    internal suspend fun writeArchive(output: OutputStream, saved: AppSettings, key: BackupKey?) {
        if (key == null) {
            exporter.export(output, saved)
            return
        }
        val encrypted = BackupCrypto.encryptingStream(output, key)
        exporter.export(encrypted, saved)
        encrypted.finish()
    }

    /**
     * Fait une sauvegarde si [decideBackup] le demande, et enregistre le résultat dans les réglages (affiché dans
     * Appareils). Ne lève pas d'exception, sauf annulation.
     */
    suspend fun run(force: Boolean): BackupOutcome = lock.withLock {
        withContext(Dispatchers.IO) { runLocked(force) }
    }

    private suspend fun runLocked(force: Boolean): BackupOutcome {
        val saved = settings.flow.first()
        val folderUri = saved.backup.folderUri ?: return BackupOutcome.Skipped(BackupDecision.NOT_CONFIGURED)
        return try {
            val folder = BackupFolder(context.contentResolver, Uri.parse(folderUri))
            folder.checkWritable()
            val entries = folder.list()
            val decision = decideBackup(
                force = force,
                folderConfigured = true,
                hasData = dao.hasAnyData(),
                dataVersion = saved.dataVersion,
                lastBackupDataVersion = saved.backup.lastDataVersion,
                backupsInFolder = entries.count { BackupNaming.isBackup(it.name) },
            )
            when (decision) {
                BackupDecision.BACKUP -> {
                    // Clé vérifiée avant de créer le moindre fichier ; sans elle, échec plutôt qu'une copie en clair.
                    val key = saved.backup.encryption?.let { keys.unlock(it) ?: throw BackupKeyUnavailableException() }
                    val name = key.useOrNull { write(folder, entries, saved, it) }
                    settings.saveBackupSuccess(folderUri, now(), name, saved.dataVersion)
                    rotate(folder, protect = name)
                    BackupOutcome.Done(name)
                }
                BackupDecision.NO_DATA -> {
                    // Seule une demande explicite mérite une réponse ; en automatique, on attend simplement des données.
                    if (force) settings.saveBackupError(folderUri, now(), "aucune donnée à sauvegarder")
                    BackupOutcome.Skipped(decision)
                }
                else -> BackupOutcome.Skipped(decision)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = when (e) {
                is BackupFolderException -> e.message.orEmpty()
                is SecurityException -> "accès au dossier refusé, choisis-le à nouveau"
                else -> e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
            }
            settings.saveBackupError(folderUri, now(), message)
            BackupOutcome.Failed(message)
        }
    }

    /** Écrit une nouvelle sauvegarde complète et renvoie son nom. */
    private suspend fun write(folder: BackupFolder, entries: List<FolderEntry>, saved: AppSettings, key: BackupKey?): String {
        val existing = entries.mapTo(HashSet()) { it.name }
        var at = LocalDateTime.now()
        // Un seul fichier par seconde, quelle que soit l'extension : la rotation les trie par date.
        while (BackupNaming.namesAt(at).any { it in existing || BackupNaming.partialName(it) in existing }) {
            at = at.plusSeconds(1)
        }
        val name = BackupNaming.fileName(at, encrypted = key != null)

        // Écriture dans un .partial (type neutre : le fournisseur n'ajoute pas d'extension), renommé à la fin.
        var target = folder.create(BackupNaming.partialName(name), "application/octet-stream")
        var renameAtEnd = true
        if (!folder.supportsRename(target)) {
            folder.delete(target)
            target = folder.create(name, if (key != null) ENCRYPTED_MIME else ZIP_MIME)
            renameAtEnd = false
        }
        try {
            BufferedOutputStream(folder.openOutput(target), BUFFER_SIZE).use { output ->
                writeArchive(output, saved, key)
                output.flush()
            }
            if (renameAtEnd) target = folder.rename(target, name)
            if (folder.list().none { it.name == name }) throw BackupFolderException("sauvegarde écrite mais introuvable dans le dossier")
            return name
        } catch (e: Throwable) {
            // Rien ne doit rester qui ressemble à une sauvegarde : ni .partial, ni .zip incomplet.
            withContext(NonCancellable) { folder.delete(target) }
            throw e
        }
    }

    /** Garde les [BackupNaming.KEEP] dernières sauvegardes et supprime les .partial abandonnés. Les échecs sont sans gravité. */
    private fun rotate(folder: BackupFolder, protect: String) {
        val entries = try {
            folder.list()
        } catch (e: Exception) {
            return
        }
        val names = entries.map { it.name }
        val doomed = (backupsToDelete(names, BackupNaming.KEEP, protect) + stalePartials(names)).toSet()
        entries.filter { it.name in doomed }.forEach { folder.delete(it.uri) }
    }

    // --- Planification (WorkManager) ---

    /** Après une modification des données : sauvegarde différée de quelques secondes pour regrouper synchro et Health Connect. */
    fun requestAfterChange() = enqueue(force = false, delaySeconds = CHANGE_DELAY_SECONDS)

    /** « Sauvegarder maintenant » : sauvegarde même sans changement, sans attendre. */
    fun requestNow() = enqueue(force = true, delaySeconds = 0)

    /**
     * Une demande remplace la précédente (REPLACE) : une demande en attente est simplement repoussée, et une
     * sauvegarde en cours est annulée (son fichier partiel supprimé) pour être refaite avec les données les plus
     * récentes. Les deux sauvegardes ne s'exécutent donc jamais en même temps sur le même nom de travail.
     */
    private fun enqueue(force: Boolean, delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(Data.Builder().putBoolean(BackupWorker.KEY_FORCE, force).build())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .apply { if (!force) setConstraints(batteryNotLow) }
            .build()
        workManager.enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** Sauvegarde quotidienne de secours (rattrape une synchro faite pendant que la batterie était faible, par exemple). */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(batteryNotLow)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(WORK_NOW)
        workManager.cancelUniqueWork(WORK_DAILY)
    }

    /** Vrai pendant qu'une sauvegarde s'écrit (pour l'écran Appareils). */
    val running: Flow<Boolean>
        get() = combine(
            workManager.getWorkInfosForUniqueWorkFlow(WORK_NOW),
            workManager.getWorkInfosForUniqueWorkFlow(WORK_DAILY),
        ) { now, daily -> (now + daily).any { it.state == WorkInfo.State.RUNNING } }

    private companion object {
        const val WORK_NOW = "boucle-backup"
        const val WORK_DAILY = "boucle-backup-daily"
        const val CHANGE_DELAY_SECONDS = 30L
        const val ZIP_MIME = "application/zip"
        const val ENCRYPTED_MIME = "application/octet-stream"
        const val BUFFER_SIZE = 64 * 1024
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        val batteryNotLow: Constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()

        /** Une sauvegarde à la fois dans le processus (travail ponctuel et quotidien peuvent se croiser). */
        val lock = Mutex()

        fun now(): String = LocalDateTime.now().format(TIME_FORMAT)
    }
}

/** Exécute [block] avec la clé (ou sans clé) et l'efface ensuite. */
private inline fun <R> BackupKey?.useOrNull(block: (BackupKey?) -> R): R =
    try {
        block(this)
    } finally {
        this?.close()
    }
