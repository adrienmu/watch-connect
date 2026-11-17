package com.boucle.app.data.export

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.boucle.app.data.RestoredSettings
import com.boucle.app.data.Settings
import com.boucle.app.data.db.ActivitySamplesEntity
import com.boucle.app.data.db.BoucleDatabase
import com.boucle.app.data.db.DailyActivityEntity
import com.boucle.app.data.db.ExternalMeasurementEntity
import com.boucle.app.data.db.HealthDao
import com.boucle.app.data.db.HrBlockEntity
import com.boucle.app.data.db.NightlyRechargeEntity
import com.boucle.app.data.db.PpiBlockEntity
import com.boucle.app.data.db.SkinTemperatureEntity
import com.boucle.app.data.db.SleepEntity
import com.boucle.app.data.db.TrainingExerciseEntity
import com.boucle.app.data.db.TrainingRouteEntity
import com.boucle.app.data.db.TrainingSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/** Nombre de lignes relues dans une sauvegarde, par table. */
data class ImportCounts(
    val dailyActivity: Int = 0,
    val activitySamples: Int = 0,
    val heartRate: Int = 0,
    val ppi: Int = 0,
    val sleep: Int = 0,
    val nightlyRecharge: Int = 0,
    val skinTemperature: Int = 0,
    val trainingSessions: Int = 0,
    val trainingExercises: Int = 0,
    val trainingRoutes: Int = 0,
    val externalMeasurements: Int = 0,
) {
    val total: Int
        get() = dailyActivity + activitySamples + heartRate + ppi + sleep + nightlyRecharge + skinTemperature +
            trainingSessions + trainingExercises + trainingRoutes + externalMeasurements
}

/** Résultat d'un import, à montrer à l'utilisateur ([message]). */
data class ImportSummary(
    val counts: ImportCounts,
    /** Date de l'export (ISO 8601), telle qu'écrite dans la sauvegarde. */
    val exportedAt: String?,
    val restored: RestoredSettings,
) {
    /** « Sauvegarde importée : 120 jours d'activité, 115 nuits, 30 séances… » */
    fun message(): String {
        val c = counts
        val parts = listOfNotNull(
            plural(c.dailyActivity, "jour d'activité", "jours d'activité"),
            plural(maxOf(c.sleep, c.nightlyRecharge), "nuit", "nuits"),
            plural(c.trainingSessions, "séance", "séances"),
            plural(c.trainingRoutes, "parcours GPS", "parcours GPS"),
            plural(c.externalMeasurements, "mesure Health Connect", "mesures Health Connect"),
            plural(c.heartRate + c.ppi + c.activitySamples + c.skinTemperature, "série de mesures en continu", "séries de mesures en continu"),
        )
        val data = if (parts.isEmpty()) "Sauvegarde importée : elle ne contenait aucune mesure." else "Sauvegarde importée : ${parts.joinToString(", ")}."
        val settings = listOfNotNull(
            "profil".takeIf { restored.profile },
            "bracelet".takeIf { restored.device },
            "dates de synchronisation".takeIf { restored.sync },
        )
        return if (settings.isEmpty()) data else "$data Repris aussi : ${settings.joinToString(", ")}."
    }

    private fun plural(n: Int, one: String, many: String): String? = when {
        n <= 0 -> null
        n == 1 -> "1 $one"
        else -> "$n $many"
    }
}

/**
 * Restauration d'une archive écrite par [DataExporter], sans serveur : le fichier est lu deux fois, en flux.
 *
 * 1. Lecture complète et validation, sans rien écrire : un fichier tronqué, endommagé ou d'un format inconnu est
 *    refusé avant toute modification.
 * 2. Écriture dans une seule transaction Room : en cas d'erreur, la base reste telle qu'elle était.
 *
 * L'import fusionne : chaque ligne remplace celle qui a la même clé (le jour, la séance, le bloc de mesures…) et
 * s'ajoute sinon. Réimporter le même fichier ne crée donc aucun doublon. Les réglages (profil, bracelet, dates
 * de synchronisation) ne sont repris que s'ils sont vides sur ce téléphone.
 */
class DataImporter(private val database: BoucleDatabase, private val settings: Settings) {

    /** Importe l'archive choisie par l'utilisateur (sélecteur de fichiers du système). */
    suspend fun import(resolver: ContentResolver, uri: Uri): ImportSummary =
        import { resolver.openInputStream(uri) ?: throw IOException("fichier inaccessible") }

    /**
     * Importe l'archive que [open] ouvre : appelé deux fois, il doit rendre à chaque fois un nouveau flux sur le
     * même contenu (fichier, flux déchiffré…). Les flux sont fermés ici.
     *
     * @throws BackupFormatException si la sauvegarde est illisible ou refusée ; rien n'est alors modifié.
     */
    suspend fun import(open: () -> InputStream): ImportSummary = withContext(Dispatchers.IO) {
        val checked = CountingSink()
        val header = open().use { BackupReader.readArchive(it, checked) }

        database.withTransaction {
            val writer = WritingSink(database.healthDao())
            open().use { BackupReader.readArchive(it, writer) }
            writer.flush()
            // Le fichier a changé entre les deux lectures : on annule tout.
            if (writer.counts() != checked.counts()) {
                throw BackupFormatException("Le fichier a changé pendant l'import. Rien n'a été modifié : réessaie.")
            }
        }

        val s = header.settings
        val restored = settings.restoreIfEmpty(
            profile = s.profile,
            device = s.device,
            lastSyncedDate = s.lastSyncedDate,
            lastSyncAt = s.lastSyncAt,
            lastHealthConnectSyncedDate = s.lastHealthConnectSyncedDate,
            lastHealthConnectSyncAt = s.lastHealthConnectSyncAt,
        )
        ImportSummary(checked.counts(), header.exportedAt, restored)
    }
}

/** Compte les lignes sans rien écrire : première lecture, de validation. */
internal open class CountingSink : BackupSink {
    private var dailyActivity = 0
    private var activitySamples = 0
    private var heartRate = 0
    private var ppi = 0
    private var sleep = 0
    private var nightlyRecharge = 0
    private var skinTemperature = 0
    private var trainingSessions = 0
    private var trainingExercises = 0
    private var trainingRoutes = 0
    private var externalMeasurements = 0

    fun counts() = ImportCounts(
        dailyActivity, activitySamples, heartRate, ppi, sleep, nightlyRecharge, skinTemperature,
        trainingSessions, trainingExercises, trainingRoutes, externalMeasurements,
    )

    override suspend fun dailyActivity(row: DailyActivityEntity) { dailyActivity++ }
    override suspend fun activitySamples(row: ActivitySamplesEntity) { activitySamples++ }
    override suspend fun hrBlock(row: HrBlockEntity) { heartRate++ }
    override suspend fun ppiBlock(row: PpiBlockEntity) { ppi++ }
    override suspend fun sleep(row: SleepEntity) { sleep++ }
    override suspend fun nightlyRecharge(row: NightlyRechargeEntity) { nightlyRecharge++ }
    override suspend fun skinTemperature(row: SkinTemperatureEntity) { skinTemperature++ }
    override suspend fun trainingSession(row: TrainingSessionEntity) { trainingSessions++ }
    override suspend fun trainingExercise(row: TrainingExerciseEntity) { trainingExercises++ }
    override suspend fun trainingRoute(row: TrainingRouteEntity) { trainingRoutes++ }
    override suspend fun externalMeasurement(row: ExternalMeasurementEntity) { externalMeasurements++ }
}

/** Écrit les lignes par lots (upsert), pour borner la mémoire ; à appeler dans une transaction, puis [flush]. */
private class WritingSink(dao: HealthDao) : CountingSink() {
    private val daily = Batch(ROWS, dao::upsertDailyActivity)
    private val samples = Batch(SERIES, dao::upsertActivitySamples)
    private val hr = Batch(SERIES, dao::upsertHrBlocks)
    private val ppiBlocks = Batch(SERIES, dao::upsertPpiBlocks)
    private val nights = Batch(ROWS, dao::upsertSleep)
    private val recharge = Batch(ROWS, dao::upsertNightlyRecharge)
    private val skin = Batch(SERIES, dao::upsertSkinTemperature)
    // Les séances, exercices et tracés sont lourds (messages protobuf bruts, points GPS) : petits lots.
    private val sessions = Batch(BLOBS, dao::upsertTrainingSessions)
    private val exercises = Batch(BLOBS, dao::upsertTrainingExercises)
    private val routes = Batch(BLOBS, dao::upsertTrainingRoutes)
    private val measurements = Batch(ROWS, dao::upsertExternalMeasurements)

    override suspend fun dailyActivity(row: DailyActivityEntity) { super.dailyActivity(row); daily.add(row) }
    override suspend fun activitySamples(row: ActivitySamplesEntity) { super.activitySamples(row); samples.add(row) }
    override suspend fun hrBlock(row: HrBlockEntity) { super.hrBlock(row); hr.add(row) }
    override suspend fun ppiBlock(row: PpiBlockEntity) { super.ppiBlock(row); ppiBlocks.add(row) }
    override suspend fun sleep(row: SleepEntity) { super.sleep(row); nights.add(row) }
    override suspend fun nightlyRecharge(row: NightlyRechargeEntity) { super.nightlyRecharge(row); recharge.add(row) }
    override suspend fun skinTemperature(row: SkinTemperatureEntity) { super.skinTemperature(row); skin.add(row) }
    override suspend fun trainingSession(row: TrainingSessionEntity) { super.trainingSession(row); sessions.add(row) }
    override suspend fun trainingExercise(row: TrainingExerciseEntity) { super.trainingExercise(row); exercises.add(row) }
    override suspend fun trainingRoute(row: TrainingRouteEntity) { super.trainingRoute(row); routes.add(row) }
    override suspend fun externalMeasurement(row: ExternalMeasurementEntity) { super.externalMeasurement(row); measurements.add(row) }

    suspend fun flush() {
        listOf(daily, samples, hr, ppiBlocks, nights, recharge, skin, sessions, exercises, routes, measurements).forEach { it.flush() }
    }

    private class Batch<T>(private val size: Int, private val write: suspend (List<T>) -> Unit) {
        private val items = ArrayList<T>(size)

        suspend fun add(item: T) {
            items.add(item)
            if (items.size >= size) flush()
        }

        suspend fun flush() {
            if (items.isEmpty()) return
            write(items.toList())
            items.clear()
        }
    }

    private companion object {
        const val ROWS = 500
        const val SERIES = 100
        const val BLOBS = 10
    }
}
