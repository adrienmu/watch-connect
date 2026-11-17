package com.boucle.app.data.backup

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Règles de la sauvegarde automatique, sans dépendance Android (testées sur la JVM) : nom des fichiers, choix des
 * anciennes sauvegardes à supprimer et décision de sauvegarder ou non.
 *
 * Le dossier de sauvegarde est choisi par l'utilisateur et peut contenir d'autres fichiers : l'app ne touche
 * qu'aux fichiers dont le nom correspond exactement à son motif, `boucle-sauvegarde-AAAAMMJJ-HHMMSS.zip` (archive
 * en clair) ou `boucle-sauvegarde-AAAAMMJJ-HHMMSS.boucle` (archive chiffrée, voir [BackupCrypto]), et
 * `….partial` pendant l'écriture, avec une date valide.
 */
object BackupNaming {
    /** Nombre de sauvegardes gardées dans le dossier, chiffrées ou non ; les plus anciennes sont supprimées. */
    const val KEEP = 7

    const val PARTIAL_SUFFIX = ".partial"
    const val PLAIN_EXTENSION = ".zip"
    const val ENCRYPTED_EXTENSION = ".boucle"

    private const val PREFIX = "boucle-sauvegarde-"
    private val STAMP_FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val BACKUP = Regex("""boucle-sauvegarde-(\d{8}-\d{6})\.(?:zip|boucle)""")
    private val PARTIAL = Regex("""boucle-sauvegarde-(\d{8}-\d{6})\.(?:zip|boucle)\.partial""")

    /**
     * `boucle-sauvegarde-20261117-180412.zip`, ou `.boucle` si [encrypted] : l'ordre alphabétique est l'ordre
     * chronologique.
     */
    fun fileName(at: LocalDateTime, encrypted: Boolean = false): String =
        PREFIX + at.withNano(0).format(STAMP_FORMAT) + if (encrypted) ENCRYPTED_EXTENSION else PLAIN_EXTENSION

    /** Les deux noms possibles (en clair et chiffré) d'une sauvegarde faite à [at]. */
    fun namesAt(at: LocalDateTime): List<String> = listOf(fileName(at, encrypted = false), fileName(at, encrypted = true))

    /** Nom du fichier temporaire, renommé en [fileName] une fois l'écriture terminée. */
    fun partialName(fileName: String): String = fileName + PARTIAL_SUFFIX

    /** Vrai pour une sauvegarde complète créée par l'app (motif exact et date valide), chiffrée ou non. */
    fun isBackup(name: String): Boolean = stampOf(BACKUP, name) != null

    /** Vrai pour une sauvegarde chiffrée créée par l'app. */
    fun isEncryptedBackup(name: String): Boolean = isBackup(name) && name.endsWith(ENCRYPTED_EXTENSION)

    /** Vrai pour une écriture temporaire de l'app (motif exact et date valide). */
    fun isPartial(name: String): Boolean = stampOf(PARTIAL, name) != null

    /** Date et heure d'une sauvegarde d'après son nom, ou `null` si le nom n'est pas celui d'une sauvegarde. */
    fun backupTime(name: String): LocalDateTime? = stampOf(BACKUP, name)

    private fun stampOf(regex: Regex, name: String): LocalDateTime? {
        val stamp = regex.matchEntire(name)?.groupValues?.get(1) ?: return null
        return try {
            LocalDateTime.parse(stamp, STAMP_FORMAT)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

/**
 * Sauvegardes à supprimer pour n'en garder que [keep] : les plus anciennes d'après la date de leur nom, `.zip` et
 * `.boucle` comptés ensemble. Les fichiers qui ne
 * sont pas des sauvegardes complètes de l'app (autres fichiers du dossier, `.partial`, noms modifiés) ne sont
 * jamais retenus, pas plus que [protect] (la sauvegarde qui vient d'être écrite, même si l'horloge du téléphone a
 * reculé et lui donne un nom plus ancien que les autres).
 */
fun backupsToDelete(names: Collection<String>, keep: Int = BackupNaming.KEEP, protect: String? = null): List<String> {
    require(keep >= 1) { "keep doit être au moins 1" }
    val newestFirst = compareByDescending<String> { BackupNaming.backupTime(it) }.thenByDescending { it }
    val backups = names.filter(BackupNaming::isBackup).distinct().sortedWith(newestFirst)
    val kept = buildSet {
        protect?.takeIf(BackupNaming::isBackup)?.let(::add)
        backups.forEach { if (size < keep) add(it) }
    }
    return backups.filterNot { it in kept }.sorted()
}

/**
 * Écritures temporaires abandonnées (app arrêtée en pleine sauvegarde) : les `.partial` de l'app, sauf
 * [inProgress]. Les sauvegardes se font une à la fois, donc tout autre `.partial` de l'app est un reste.
 */
fun stalePartials(names: Collection<String>, inProgress: String? = null): List<String> =
    names.filter { BackupNaming.isPartial(it) && it != inProgress }.distinct().sorted()

enum class BackupDecision {
    /** Écrire une nouvelle sauvegarde. */
    BACKUP,

    /** Pas de dossier choisi : sauvegarde automatique désactivée. */
    NOT_CONFIGURED,

    /** Base vide : une sauvegarde vide ne ferait que pousser les vraies vers la sortie. */
    NO_DATA,

    /** Rien n'a changé depuis la dernière sauvegarde, et elle est encore dans le dossier. */
    UNCHANGED,
}

/**
 * Faut-il écrire une sauvegarde ?
 *
 * @param force demande explicite (« Sauvegarder maintenant ») : on sauvegarde même sans changement.
 * @param dataVersion compteur augmenté à chaque modification des données (synchro, lecture Health Connect, import).
 * @param lastBackupDataVersion valeur de [dataVersion] lue au début de la dernière sauvegarde réussie dans ce
 *   dossier, `null` s'il n'y en a pas eu (ou si le dossier a changé depuis).
 * @param backupsInFolder nombre de sauvegardes de l'app présentes dans le dossier : s'il n'y en a plus (supprimées à
 *   la main), on en refait une même sans changement.
 */
fun decideBackup(
    force: Boolean,
    folderConfigured: Boolean,
    hasData: Boolean,
    dataVersion: Long,
    lastBackupDataVersion: Long?,
    backupsInFolder: Int,
): BackupDecision = when {
    !folderConfigured -> BackupDecision.NOT_CONFIGURED
    !hasData -> BackupDecision.NO_DATA
    force -> BackupDecision.BACKUP
    backupsInFolder == 0 -> BackupDecision.BACKUP
    lastBackupDataVersion == null -> BackupDecision.BACKUP
    dataVersion != lastBackupDataVersion -> BackupDecision.BACKUP
    else -> BackupDecision.UNCHANGED
}
