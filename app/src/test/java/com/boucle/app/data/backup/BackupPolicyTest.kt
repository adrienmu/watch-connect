package com.boucle.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BackupPolicyTest {

    private fun name(day: Int, hour: Int = 18) = BackupNaming.fileName(LocalDateTime.of(2026, 11, day, hour, 4, 12))

    @Test
    fun fileNameIsTimestampedAndRecognized() {
        val n = BackupNaming.fileName(LocalDateTime.of(2026, 1, 7, 9, 5, 3, 999_000_000))
        assertEquals("boucle-sauvegarde-20260107-090503.zip", n)
        assertTrue(BackupNaming.isBackup(n))
        assertEquals(LocalDateTime.of(2026, 1, 7, 9, 5, 3), BackupNaming.backupTime(n))
        assertEquals("$n.partial", BackupNaming.partialName(n))
        assertTrue(BackupNaming.isPartial(BackupNaming.partialName(n)))
        assertFalse(BackupNaming.isPartial(n))
        assertFalse(BackupNaming.isBackup(BackupNaming.partialName(n)))
    }

    @Test
    fun onlyTheExactPatternWithAValidDateIsRecognized() {
        listOf(
            "boucle-sauvegarde-20261117-180412.zip (1)",
            "boucle-sauvegarde-20261117-180412 (1).zip",
            "Boucle-sauvegarde-20261117-180412.zip",
            "boucle-sauvegarde-20261117-180412.ZIP",
            "boucle-sauvegarde-20261117-180412.zip.bak",
            "copie-boucle-sauvegarde-20261117-180412.zip",
            "boucle-sauvegarde-2026111-180412.zip",
            "boucle-sauvegarde-20261117180412.zip",
            "boucle-sauvegarde-20261332-180412.zip",
            "boucle-sauvegarde-20261117-256199.zip",
            "boucle-sauvegarde-20260229-120000.zip",
            "boucle-export-2026-11-17.zip",
            "photo.jpg",
            "",
        ).forEach {
            assertFalse(it, BackupNaming.isBackup(it))
            assertNull(it, BackupNaming.backupTime(it))
        }
        assertTrue(BackupNaming.isBackup("boucle-sauvegarde-20280229-235959.zip"))
    }

    @Test
    fun rotationKeepsTheMostRecentAndIgnoresForeignFiles() {
        val backups = (1..10).map { name(it) }
        val foreign = listOf(
            "photo.jpg",
            "boucle-export-2026-11-01.zip",
            "boucle-sauvegarde-20261101-180412.zip (1)",
            "boucle-sauvegarde-20260101-000000.txt",
            BackupNaming.partialName(name(1)),
            BackupNaming.partialName(name(11)),
        )
        val doomed = backupsToDelete((foreign + backups).shuffled(java.util.Random(1)), keep = 7)
        assertEquals(listOf(name(1), name(2), name(3)), doomed)
        assertTrue(doomed.none { it in foreign })
    }

    private fun encName(day: Int, hour: Int = 18) = BackupNaming.fileName(LocalDateTime.of(2026, 11, day, hour, 4, 12), encrypted = true)

    @Test
    fun encryptedFileNameIsRecognized() {
        val n = BackupNaming.fileName(LocalDateTime.of(2026, 1, 7, 9, 5, 3), encrypted = true)
        assertEquals("boucle-sauvegarde-20260107-090503.boucle", n)
        assertTrue(BackupNaming.isBackup(n))
        assertTrue(BackupNaming.isEncryptedBackup(n))
        assertFalse(BackupNaming.isEncryptedBackup(BackupNaming.fileName(LocalDateTime.of(2026, 1, 7, 9, 5, 3))))
        assertEquals(LocalDateTime.of(2026, 1, 7, 9, 5, 3), BackupNaming.backupTime(n))
        assertTrue(BackupNaming.isPartial(BackupNaming.partialName(n)))
        assertFalse(BackupNaming.isBackup(BackupNaming.partialName(n)))
        assertEquals(
            listOf("boucle-sauvegarde-20260107-090503.zip", n),
            BackupNaming.namesAt(LocalDateTime.of(2026, 1, 7, 9, 5, 3)),
        )
        listOf(
            "boucle-sauvegarde-20260107-090503.BOUCLE",
            "boucle-sauvegarde-20260107-090503.boucle.zip",
            "boucle-sauvegarde-20260107-090503.boucle (1)",
            "boucle-sauvegarde-20261332-090503.boucle",
            "autre-20260107-090503.boucle",
        ).forEach { assertFalse(it, BackupNaming.isBackup(it)) }
    }

    @Test
    fun rotationCountsBothExtensionsTogether() {
        // 4 anciennes en clair, puis 6 chiffrées : on garde les 7 plus récentes, quelle que soit l'extension.
        val plain = (1..4).map { name(it) }
        val encrypted = (5..10).map { encName(it) }
        val foreign = listOf("notes.boucle", "boucle-sauvegarde-20261101-180412.boucle.bak", BackupNaming.partialName(encName(11)))
        val doomed = backupsToDelete((plain + encrypted + foreign).shuffled(java.util.Random(2)), keep = 7)
        assertEquals(listOf(name(1), name(2), name(3)), doomed)

        // Ordre chronologique et non alphabétique : « .boucle » plus ancien qu'un « .zip » plus récent.
        val mixed = listOf(encName(1), name(2), encName(3), name(4))
        assertEquals(listOf(encName(1), name(2)), backupsToDelete(mixed, keep = 2))
        // La sauvegarde chiffrée qui vient d'être écrite est protégée même si son nom paraît le plus ancien.
        assertEquals(listOf(name(2)), backupsToDelete(listOf(encName(1), name(2), name(3)), keep = 2, protect = encName(1)))
    }

    @Test
    fun stalePartialsIncludeEncryptedOnes() {
        val p = BackupNaming.partialName(encName(3))
        assertEquals(listOf(p), stalePartials(listOf(p, encName(3), "x.boucle.partial")))
    }

    @Test
    fun rotationDeletesNothingUnderTheLimit() {
        assertEquals(emptyList<String>(), backupsToDelete(listOf(name(1), name(2), "notes.txt"), keep = 7))
        assertEquals(emptyList<String>(), backupsToDelete((1..7).map { name(it) }, keep = 7))
        assertEquals(emptyList<String>(), backupsToDelete(emptyList(), keep = 7))
    }

    @Test
    fun rotationNeverDeletesTheBackupJustWritten() {
        // Horloge reculée : la nouvelle sauvegarde porte le nom le plus ancien.
        val existing = (10..16).map { name(it) }
        val justWritten = name(1)
        val doomed = backupsToDelete(existing + justWritten, keep = 7, protect = justWritten)
        assertFalse(justWritten in doomed)
        assertEquals(listOf(name(10)), doomed)
    }

    @Test
    fun rotationIgnoresDuplicatesAndUsesTheDefaultLimit() {
        val names = (1..9).map { name(it) }
        assertEquals(listOf(name(1), name(2)), backupsToDelete(names + names))
        assertEquals(7, BackupNaming.KEEP)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rotationRefusesToKeepNothing() {
        backupsToDelete(listOf(name(1)), keep = 0)
    }

    @Test
    fun stalePartialsAreOnlyTheAppsOwn() {
        val ours = BackupNaming.partialName(name(3))
        val current = BackupNaming.partialName(name(4))
        val names = listOf(ours, current, name(3), "film.mkv.partial", "boucle-sauvegarde-x.zip.partial", "notes.partial")
        assertEquals(listOf(ours), stalePartials(names, inProgress = current))
        assertEquals(listOf(ours, current), stalePartials(names))
    }

    @Test
    fun decisionWithoutFolderOrData() {
        assertEquals(BackupDecision.NOT_CONFIGURED, decide(folderConfigured = false, force = true))
        assertEquals(BackupDecision.NO_DATA, decide(hasData = false))
        assertEquals(BackupDecision.NO_DATA, decide(hasData = false, force = true))
    }

    @Test
    fun decisionBacksUpOnlyWhenSomethingChanged() {
        assertEquals(BackupDecision.UNCHANGED, decide(dataVersion = 5, lastBackupDataVersion = 5))
        assertEquals(BackupDecision.BACKUP, decide(dataVersion = 6, lastBackupDataVersion = 5))
        // Jamais sauvegardé dans ce dossier (ou dossier changé).
        assertEquals(BackupDecision.BACKUP, decide(dataVersion = 5, lastBackupDataVersion = null))
        // Données remises en arrière (réglages restaurés) : différent, donc à sauvegarder.
        assertEquals(BackupDecision.BACKUP, decide(dataVersion = 2, lastBackupDataVersion = 5))
    }

    @Test
    fun decisionBacksUpWhenForcedOrWhenTheFolderHasNoBackupLeft() {
        assertEquals(BackupDecision.BACKUP, decide(force = true, dataVersion = 5, lastBackupDataVersion = 5))
        assertEquals(BackupDecision.BACKUP, decide(dataVersion = 5, lastBackupDataVersion = 5, backupsInFolder = 0))
    }

    private fun decide(
        force: Boolean = false,
        folderConfigured: Boolean = true,
        hasData: Boolean = true,
        dataVersion: Long = 1,
        lastBackupDataVersion: Long? = 1,
        backupsInFolder: Int = 3,
    ) = decideBackup(force, folderConfigured, hasData, dataVersion, lastBackupDataVersion, backupsInFolder)
}
