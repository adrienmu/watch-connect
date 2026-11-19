package com.boucle.app.withings

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.boucle.app.data.db.ActivityEditEntity
import com.boucle.app.data.db.ActivitySamplesEntity
import com.boucle.app.data.db.BoucleDatabase
import com.boucle.app.data.db.DailyActivityEntity
import com.boucle.app.data.db.ExternalMeasurementEntity
import com.boucle.app.data.db.HrBlockEntity
import com.boucle.app.data.db.NightlyRechargeEntity
import com.boucle.app.data.db.PpiBlockEntity
import com.boucle.app.data.db.SkinTemperatureEntity
import com.boucle.app.data.db.SleepEntity
import com.boucle.app.data.db.TrainingExerciseEntity
import com.boucle.app.data.db.TrainingRouteEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.data.export.BackupFormatException
import com.boucle.app.data.export.BackupSink
import com.boucle.app.data.export.ImportCounts
import com.boucle.app.data.export.ImportWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** Résultat d'un import de l'archive Withings, à montrer à l'utilisateur ([message]). */
data class WithingsImportSummary(val report: WithingsReport, val read: ImportCounts, val added: ImportCounts) {
    /** « Historique Withings importé, du 12/03/2019 au 18/11/2026. Ajoutés : 2 810 jours d'activité, 2 650 nuits… » */
    fun message(): String {
        val period = report.firstDay?.let { first ->
            report.lastDay?.let { last -> ", du ${first.format(DAY_FORMAT)} au ${last.format(DAY_FORMAT)}" }
        }.orEmpty()
        val addedParts = parts(added)
        val keptParts = parts(read - added)
        return buildString {
            append("Historique Withings importé").append(period).append('.')
            append(if (addedParts.isEmpty()) " Rien d'ajouté : tout était déjà là." else " Ajoutés : ${addedParts.joinToString(", ")}.")
            if (addedParts.isNotEmpty() && keptParts.isNotEmpty()) append(" Déjà présents (conservés) : ${keptParts.joinToString(", ")}.")
        }
    }

    private fun parts(c: ImportCounts): List<String> = listOfNotNull(
        plural(c.dailyActivity, "jour d'activité", "jours d'activité"),
        plural(c.sleep, "nuit", "nuits"),
        plural(c.trainingSessions, "séance", "séances"),
        plural(c.trainingRoutes, "parcours GPS", "parcours GPS"),
        plural(c.externalMeasurements, "mesure (poids, SpO2…)", "mesures (poids, SpO2…)"),
        plural(c.heartRate, "heure de fréquence cardiaque", "heures de fréquence cardiaque"),
    )

    private fun plural(n: Int, one: String, many: String): String? = when {
        n <= 0 -> null
        n == 1 -> "1 $one"
        else -> "$n $many"
    }

    private companion object {
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}

/**
 * Import de l'archive d'export Withings choisie par l'utilisateur : tout l'historique de la montre et de la balance.
 *
 * Comme l'import d'une sauvegarde ([com.boucle.app.data.export.DataImporter]), il n'ajoute que ce qui manque, dans
 * une seule transaction : un jour, une nuit ou une séance déjà sur le téléphone (bracelet Polar, Health Connect ou
 * import précédent) n'est jamais remplacé, et réimporter une archive plus récente ne fait qu'ajouter les nouveaux
 * jours. L'archive est d'abord copiée dans le cache de l'app, pour lire ses fichiers dans l'ordre voulu.
 */
class WithingsImporter(private val database: BoucleDatabase, private val cacheDir: File) {

    /** Vrai si le fichier est un ZIP qui contient au moins un fichier de l'archive Withings. */
    suspend fun isWithingsArchive(resolver: ContentResolver, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    generateSequence { zip.nextEntry }.take(MAX_ENTRIES_CHECKED).any { WithingsExport.isWithingsFile(it.name) }
                }
            } ?: false
        }.getOrDefault(false)
    }

    /** @throws BackupFormatException si le fichier n'est pas une archive Withings lisible ; rien n'est alors modifié. */
    suspend fun import(resolver: ContentResolver, uri: Uri, zone: ZoneId = ZoneId.systemDefault()): WithingsImportSummary =
        withContext(Dispatchers.IO) {
            val copy = File.createTempFile("withings", ".zip", cacheDir)
            try {
                (resolver.openInputStream(uri) ?: throw IOException("fichier inaccessible")).use { input ->
                    copy.outputStream().use { input.copyTo(it) }
                }
                val zip = try {
                    ZipFile(copy)
                } catch (e: ZipException) {
                    throw BackupFormatException(NOT_AN_ARCHIVE_MESSAGE)
                }
                zip.use { import(it, zone) }
            } finally {
                copy.delete()
            }
        }

    private suspend fun import(zip: ZipFile, zone: ZoneId): WithingsImportSummary = database.withTransaction {
        val dao = database.healthDao()
        val writer = ImportWriter(dao)
        val filter = WithingsMergeFilter(
            out = writer,
            hrDates = dao.hrBlockDates().toSet(),
            activityDates = dao.activitySampleDates().toSet(),
            sessions = dao.allTrainingSessions().filterNot { it.path.startsWith(WithingsExport.PREFIX) },
            measurements = dao.allExternalMeasurements().filterNot { it.id.startsWith(WithingsExport.PREFIX) },
        )
        val report = WithingsExport.read(zip, zone, filter)
        writer.flush()
        if (report.files.isEmpty()) throw BackupFormatException(NOT_AN_ARCHIVE_MESSAGE)
        WithingsImportSummary(report, writer.counts(), writer.added())
    }

    companion object {
        const val NOT_AN_ARCHIVE_MESSAGE =
            "Ce fichier n'est pas l'archive d'export Withings (le fichier ZIP reçu par e-mail). Rien n'a été modifié."
        private const val MAX_ENTRIES_CHECKED = 500
    }
}

/**
 * Écarte de l'archive ce que le téléphone a déjà sous une autre clé, et qui ferait donc doublon malgré
 * « n'ajouter que ce qui manque » :
 *
 *  - la FC et les pas à la minute d'un jour qui en a déjà (les blocs du bracelet Polar ne commencent pas aux mêmes
 *    heures que ceux de l'archive : les deux s'additionneraient) ;
 *  - une séance déjà lue dans Health Connect ou enregistrée autrement au même moment, avec son tracé ;
 *  - une mesure (pesée, SpO2, VFC) déjà lue dans Health Connect au même instant.
 */
internal class WithingsMergeFilter(
    private val out: BackupSink,
    private val hrDates: Set<String>,
    private val activityDates: Set<String>,
    sessions: List<TrainingSessionEntity>,
    measurements: List<ExternalMeasurementEntity>,
) : BackupSink {
    private val sessionStarts: Map<String, List<LocalDateTime>> = sessions
        .mapNotNull { s -> s.startTime?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } }
        .groupBy { it.toLocalDate().toString() }
    private val measurementKeys: Set<String> = measurements
        .mapNotNull { m -> runCatching { Instant.parse(m.time).epochSecond }.getOrNull()?.let { "${m.type}|$it" } }
        .toSet()
    private val skippedSessions = HashSet<String>()

    override suspend fun dailyActivity(row: DailyActivityEntity) = out.dailyActivity(row)
    override suspend fun activitySamples(row: ActivitySamplesEntity) { if (row.date !in activityDates) out.activitySamples(row) }
    override suspend fun hrBlock(row: HrBlockEntity) { if (row.date !in hrDates) out.hrBlock(row) }
    override suspend fun ppiBlock(row: PpiBlockEntity) = out.ppiBlock(row)
    override suspend fun sleep(row: SleepEntity) = out.sleep(row)
    override suspend fun nightlyRecharge(row: NightlyRechargeEntity) = out.nightlyRecharge(row)
    override suspend fun skinTemperature(row: SkinTemperatureEntity) = out.skinTemperature(row)
    override suspend fun trainingExercise(row: TrainingExerciseEntity) = out.trainingExercise(row)
    override suspend fun activityEdit(row: ActivityEditEntity) = out.activityEdit(row)

    override suspend fun trainingSession(row: TrainingSessionEntity) {
        val start = row.startTime?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val known = start != null && listOf(-1L, 0L, 1L).any { shift ->
            sessionStarts[LocalDate.parse(row.date).plusDays(shift).toString()].orEmpty()
                .any { abs(Duration.between(it, start).seconds) <= SAME_SESSION_SEC }
        }
        if (known) skippedSessions += row.path else out.trainingSession(row)
    }

    override suspend fun trainingRoute(row: TrainingRouteEntity) {
        if (row.sessionPath !in skippedSessions) out.trainingRoute(row)
    }

    override suspend fun externalMeasurement(row: ExternalMeasurementEntity) {
        val key = runCatching { Instant.parse(row.time).epochSecond }.getOrNull()?.let { "${row.type}|$it" }
        if (key !in measurementKeys) out.externalMeasurement(row)
    }

    private companion object {
        /** Deux séances qui commencent à moins de 2 minutes d'écart sont la même. */
        const val SAME_SESSION_SEC = 120L
    }
}
