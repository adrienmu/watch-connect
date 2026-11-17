package com.boucle.app.data.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import java.io.OutputStream

/** Le dossier de sauvegarde ne peut pas être utilisé ; [message] est affiché tel quel dans l'écran Appareils. */
class BackupFolderException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Un fichier du dossier de sauvegarde. */
internal data class FolderEntry(val name: String, val uri: Uri)

/**
 * Accès au dossier choisi par l'utilisateur (arborescence SAF, `ACTION_OPEN_DOCUMENT_TREE`) via
 * [DocumentsContract] : lister, créer, renommer et supprimer des fichiers, sans jamais descendre dans les
 * sous-dossiers.
 */
internal class BackupFolder(private val resolver: ContentResolver, private val treeUri: Uri) {

    private val rootUri: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    /**
     * Vérifie que le dossier est encore utilisable : permission persistante toujours accordée, dossier présent
     * (carte SD insérée, dossier non supprimé) et ouvert à l'écriture.
     */
    fun checkWritable() {
        if (!hasPermission(resolver, treeUri)) throw BackupFolderException("accès au dossier perdu, choisis-le à nouveau")
        val (mime, flags) = query(rootUri, arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)) { c ->
            c.getString(0) to c.getInt(1)
        } ?: throw BackupFolderException("dossier introuvable (supprimé, ou carte SD retirée ?)")
        if (mime != Document.MIME_TYPE_DIR) throw BackupFolderException("ce n'est plus un dossier")
        if (flags and Document.FLAG_DIR_SUPPORTS_CREATE == 0) throw BackupFolderException("dossier en lecture seule")
    }

    /** Fichiers directement dans le dossier (les sous-dossiers sont ignorés). */
    fun list(): List<FolderEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(rootUri))
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val cursor = resolver.query(children, projection, null, null, null)
            ?: throw BackupFolderException("contenu du dossier illisible")
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == Document.MIME_TYPE_DIR) continue
                    add(FolderEntry(name, DocumentsContract.buildDocumentUriUsingTree(treeUri, id)))
                }
            }
        }
    }

    fun create(name: String, mimeType: String): Uri =
        DocumentsContract.createDocument(resolver, rootUri, mimeType, name)
            ?: throw BackupFolderException("impossible de créer un fichier dans le dossier")

    /** Vrai si le fournisseur du dossier sait renommer [uri] (tous ne le font pas). */
    fun supportsRename(uri: Uri): Boolean =
        (query(uri, arrayOf(Document.COLUMN_FLAGS)) { it.getInt(0) } ?: 0) and Document.FLAG_SUPPORTS_RENAME != 0

    /** Renomme [uri] ; renvoie son nouvel URI (il peut changer selon le fournisseur). */
    fun rename(uri: Uri, name: String): Uri =
        DocumentsContract.renameDocument(resolver, uri, name) ?: uri

    fun openOutput(uri: Uri): OutputStream =
        resolver.openOutputStream(uri, "w") ?: throw BackupFolderException("fichier de sauvegarde inaccessible")

    /** Supprime [uri] ; renvoie faux en cas d'échec (le fichier reste, sans conséquence pour la sauvegarde). */
    fun delete(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (e: Exception) {
        false
    }

    private fun <T> query(uri: Uri, projection: Array<String>, read: (android.database.Cursor) -> T): T? =
        resolver.query(uri, projection, null, null, null)?.use { if (it.moveToFirst()) read(it) else null }

    companion object {
        /** Droits demandés au système pour garder l'accès au dossier après redémarrage. */
        const val PERSISTABLE_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /** Nom affichable du dossier choisi, ou `null` s'il est illisible. */
        fun displayName(resolver: ContentResolver, treeUri: Uri): String? = try {
            val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            resolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }

        /** Vrai si la permission persistante sur [treeUri] (lecture et écriture) est toujours accordée. */
        fun hasPermission(resolver: ContentResolver, treeUri: Uri): Boolean =
            resolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission && it.isReadPermission }
    }
}
