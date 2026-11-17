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
import com.boucle.app.data.backup.BackupCrypto
import com.boucle.app.data.backup.BackupKey
import com.boucle.app.data.backup.EncryptedHeader
import com.boucle.app.data.backup.WrongPassphraseException
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/** Nombre de lignes d'une sauvegarde, par table (relues, ou ajoutées à la base). */
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

    operator fun minus(other: ImportCounts) = ImportCounts(
        dailyActivity - other.dailyActivity,
        activitySamples - other.activitySamples,
        heartRate - other.heartRate,
        ppi - other.ppi,
        sleep - other.sleep,
        nightlyRecharge - other.nightlyRecharge,
        skinTemperature - other.skinTemperature,
        trainingSessions - other.trainingSessions,
        trainingExercises - other.trainingExercises,
        trainingRoutes - other.trainingRoutes,
        externalMeasurements - other.externalMeasurements,
    )
}

/** Résultat d'un import, à montrer à l'utilisateur ([message]). */
data class ImportSummary(
    /** Lignes relues dans la sauvegarde. */
    val read: ImportCounts,
    /** Lignes ajoutées, parce qu'absentes du téléphone. */
    val added: ImportCounts,
    /** Date de l'export (ISO 8601), telle qu'écrite dans la sauvegarde. */
    val exportedAt: String?,
    val restored: RestoredSettings,
) {
    /** Lignes déjà présentes sur le téléphone : conservées telles quelles, la sauvegarde ne les a pas remplacées. */
    val kept: ImportCounts get() = read - added

    /** « Sauvegarde importée. Ajoutés : 120 jours d'activité, 115 nuits. Déjà présents (conservés) : 3 séances. » */
    fun message(): String {
        val data = if (read.total == 0) {
            "Sauvegarde importée : elle ne contenait aucune mesure."
        } else {
            val addedParts = parts(added)
            val keptParts = parts(kept)
            buildString {
                append("Sauvegarde importée.")
                append(if (addedParts.isEmpty()) " Rien d'ajouté." else " Ajoutés : ${addedParts.joinToString(", ")}.")
                if (keptParts.isNotEmpty()) append(" Déjà présents (conservés) : ${keptParts.joinToString(", ")}.")
            }
        }
        val settings = listOfNotNull(
            "profil".takeIf { restored.profile },
            "bracelet".takeIf { restored.device },
            "dates de synchronisation".takeIf { restored.sync },
        )
        return if (settings.isEmpty()) data else "$data Repris aussi : ${settings.joinToString(", ")}."
    }

    private fun parts(c: ImportCounts): List<String> = listOfNotNull(
        plural(c.dailyActivity, "jour d'activité", "jours d'activité"),
        plural(maxOf(c.sleep, c.nightlyRecharge), "nuit", "nuits"),
        plural(c.trainingSessions, "séance", "séances"),
        plural(c.trainingRoutes, "parcours GPS", "parcours GPS"),
        plural(c.externalMeasurements, "mesure Health Connect", "mesures Health Connect"),
        plural(c.heartRate + c.ppi + c.activitySamples + c.skinTemperature, "série de mesures en continu", "séries de mesures en continu"),
    )

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
 * L'import n'ajoute que ce qui manque : une ligne n'est écrite que si sa clé (le jour, la séance, le bloc de
 * mesures…) est absente de la base, et une ligne déjà présente n'est jamais remplacée, même si celle de la
 * sauvegarde diffère. La base du téléphone peut en effet être plus récente que la sauvegarde (une journée
 * synchronisée après une sauvegarde de la mi-journée) : la remplacer ferait perdre des données. Une séance déjà
 * présente garde ses exercices et son tracé tels quels ; une séance ajoutée l'est avec tous les siens. Réimporter
 * le même fichier ne change donc rien. Les réglages (profil, bracelet, dates de synchronisation) ne sont repris que
 * s'ils sont vides sur ce téléphone. Les tables n'ont pas de clé étrangère : l'ordre d'écriture est sans effet.
 */
class DataImporter(private val database: BoucleDatabase, private val settings: Settings) {

    /** Importe l'archive choisie par l'utilisateur (sélecteur de fichiers du système), non chiffrée. */
    suspend fun import(resolver: ContentResolver, uri: Uri): ImportSummary = import { openFile(resolver, uri) }

    /**
     * Importe une sauvegarde chiffrée avec [key], dérivée une seule fois de la phrase de passe
     * ([BackupCrypto.deriveKey]) : les deux lectures rouvrent le fichier et le redéchiffrent en flux, sans copie en
     * clair. [key] n'est pas effacée ici.
     *
     * @throws WrongPassphraseException si [key] ne correspond pas au fichier ; rien n'est alors modifié.
     */
    suspend fun importEncrypted(resolver: ContentResolver, uri: Uri, key: BackupKey): ImportSummary =
        import { BackupCrypto.decryptingStream(BufferedInputStream(openFile(resolver, uri), BUFFER_SIZE), key) }

    /** Vrai si le fichier commence par l'en-tête des sauvegardes chiffrées. */
    suspend fun isEncrypted(resolver: ContentResolver, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        openFile(resolver, uri).use { BackupCrypto.isEncrypted(it) }
    }

    /** En-tête d'une sauvegarde chiffrée (sel et itérations, pour dériver la clé). */
    suspend fun readEncryptedHeader(resolver: ContentResolver, uri: Uri): EncryptedHeader = withContext(Dispatchers.IO) {
        openFile(resolver, uri).use { BackupCrypto.readHeader(it) }
    }

    private fun openFile(resolver: ContentResolver, uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("fichier inaccessible")

    /**
     * Importe l'archive que [open] ouvre : appelé deux fois, il doit rendre à chaque fois un nouveau flux sur le
     * même contenu (fichier, flux déchiffré…). Les flux sont fermés ici.
     *
     * @throws BackupFormatException si la sauvegarde est illisible ou refusée ; rien n'est alors modifié.
     */
    suspend fun import(open: () -> InputStream): ImportSummary = withContext(Dispatchers.IO) {
        val checked = CountingSink()
        val header = open().use {
            val header = BackupReader.readArchive(it, checked)
            // Lu jusqu'au bout : un fichier chiffré n'est authentifié qu'une fois son dernier segment vérifié.
            drain(it)
            header
        }

        val added = database.withTransaction {
            val writer = ImportWriter(database.healthDao())
            open().use { BackupReader.readArchive(it, writer) }
            writer.flush()
            // Le fichier a changé entre les deux lectures : on annule tout.
            if (writer.counts() != checked.counts()) {
                throw BackupFormatException("Le fichier a changé pendant l'import. Rien n'a été modifié : réessaie.")
            }
            writer.added()
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
        ImportSummary(checked.counts(), added, header.exportedAt, restored)
    }
}

private const val BUFFER_SIZE = 64 * 1024

private fun drain(input: InputStream) {
    val buffer = ByteArray(BUFFER_SIZE)
    do {
        val n = input.read(buffer)
    } while (n >= 0)
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

/**
 * Ajoute à la base les lignes absentes, par lots pour borner la mémoire, sans jamais remplacer une ligne existante
 * (voir [DataImporter]) ; à appeler dans une transaction, puis [flush]. [counts] donne les lignes relues, [added]
 * celles réellement ajoutées.
 *
 * Séances : chacune est insérée seule, aussitôt lue, pour savoir si elle était déjà là. Ses exercices et son tracé
 * (qui la suivent dans le fichier) ne sont écrits que si elle vient d'être ajoutée. Pour un exercice ou un tracé
 * sans séance dans la sauvegarde (`unlinked…`), même règle d'après la base : rien n'est touché si la séance y est.
 */
internal class ImportWriter(private val dao: HealthDao) : CountingSink() {
    private var addedDaily = 0
    private var addedSamples = 0
    private var addedHr = 0
    private var addedPpi = 0
    private var addedSleep = 0
    private var addedRecharge = 0
    private var addedSkin = 0
    private var addedSessions = 0
    private var addedExercises = 0
    private var addedRoutes = 0
    private var addedMeasurements = 0

    private val daily = Batch(ROWS, dao::insertDailyActivityIfAbsent) { addedDaily += it }
    private val samples = Batch(SERIES, dao::insertActivitySamplesIfAbsent) { addedSamples += it }
    private val hr = Batch(SERIES, dao::insertHrBlocksIfAbsent) { addedHr += it }
    private val ppiBlocks = Batch(SERIES, dao::insertPpiBlocksIfAbsent) { addedPpi += it }
    private val nights = Batch(ROWS, dao::insertSleepIfAbsent) { addedSleep += it }
    private val recharge = Batch(ROWS, dao::insertNightlyRechargeIfAbsent) { addedRecharge += it }
    private val skin = Batch(SERIES, dao::insertSkinTemperatureIfAbsent) { addedSkin += it }
    // Les exercices et tracés sont lourds (messages protobuf bruts, points GPS) : petits lots.
    private val exercises = Batch(BLOBS, dao::insertTrainingExercisesIfAbsent) { addedExercises += it }
    private val routes = Batch(BLOBS, dao::insertTrainingRoutesIfAbsent) { addedRoutes += it }
    private val measurements = Batch(ROWS, dao::insertExternalMeasurementsIfAbsent) { addedMeasurements += it }

    /** Séances ajoutées par cet import : leurs exercices et tracés sont importés. */
    private val insertedSessions = HashSet<String>()

    /** Séances déjà présentes avant l'import : leurs exercices et tracés ne sont pas touchés. */
    private val existingSessions = HashSet<String>()

    fun added() = ImportCounts(
        addedDaily, addedSamples, addedHr, addedPpi, addedSleep, addedRecharge, addedSkin,
        addedSessions, addedExercises, addedRoutes, addedMeasurements,
    )

    override suspend fun dailyActivity(row: DailyActivityEntity) { super.dailyActivity(row); daily.add(row) }
    override suspend fun activitySamples(row: ActivitySamplesEntity) { super.activitySamples(row); samples.add(row) }
    override suspend fun hrBlock(row: HrBlockEntity) { super.hrBlock(row); hr.add(row) }
    override suspend fun ppiBlock(row: PpiBlockEntity) { super.ppiBlock(row); ppiBlocks.add(row) }
    override suspend fun sleep(row: SleepEntity) { super.sleep(row); nights.add(row) }
    override suspend fun nightlyRecharge(row: NightlyRechargeEntity) { super.nightlyRecharge(row); recharge.add(row) }
    override suspend fun skinTemperature(row: SkinTemperatureEntity) { super.skinTemperature(row); skin.add(row) }
    override suspend fun externalMeasurement(row: ExternalMeasurementEntity) { super.externalMeasurement(row); measurements.add(row) }

    override suspend fun trainingSession(row: TrainingSessionEntity) {
        super.trainingSession(row)
        // Séance en double dans le fichier : la première décide.
        if (row.path in insertedSessions || row.path in existingSessions) return
        if (dao.insertTrainingSessionIfAbsent(row) != NOT_INSERTED) {
            insertedSessions += row.path
            addedSessions++
        } else {
            existingSessions += row.path
        }
    }

    override suspend fun trainingExercise(row: TrainingExerciseEntity) {
        super.trainingExercise(row)
        if (childAllowed(row.sessionPath)) exercises.add(row)
    }

    override suspend fun trainingRoute(row: TrainingRouteEntity) {
        super.trainingRoute(row)
        if (childAllowed(row.sessionPath)) routes.add(row)
    }

    /** Vrai si l'exercice ou le tracé de la séance [path] peut être ajouté (s'il n'existe pas déjà). */
    private suspend fun childAllowed(path: String): Boolean = when {
        path in insertedSessions -> true
        path in existingSessions -> false
        dao.hasTrainingSession(path) -> {
            existingSessions += path
            false
        }
        // Ni dans la sauvegarde ni dans la base : exercice ou tracé orphelin, ajouté s'il manque.
        else -> true
    }

    suspend fun flush() {
        listOf(daily, samples, hr, ppiBlocks, nights, recharge, skin, exercises, routes, measurements).forEach { it.flush() }
    }

    private class Batch<T>(
        private val size: Int,
        private val write: suspend (List<T>) -> List<Long>,
        private val onAdded: (Int) -> Unit,
    ) {
        private val items = ArrayList<T>(size)

        suspend fun add(item: T) {
            items.add(item)
            if (items.size >= size) flush()
        }

        suspend fun flush() {
            if (items.isEmpty()) return
            onAdded(write(items.toList()).count { it != NOT_INSERTED })
            items.clear()
        }
    }

    private companion object {
        const val ROWS = 500
        const val SERIES = 100
        const val BLOBS = 10

        /** Rowid renvoyé par Room pour une ligne ignorée (clé déjà présente). */
        const val NOT_INSERTED = -1L
    }
}
