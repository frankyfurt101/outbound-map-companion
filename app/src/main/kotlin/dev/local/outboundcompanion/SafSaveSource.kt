package dev.local.outboundcompanion

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import outbound.core.Candidate
import outbound.core.GrantLevel
import outbound.core.GrantScope
import java.io.FileInputStream

class SafException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Read-only access to Eden's Savegames folder through the Storage Access Framework. */
class SafSaveSource(private val context: Context) {

    data class Entry(val candidate: Candidate, val docUri: Uri)

    data class Listing(val entries: List<Entry>, val modifiedPopulated: Boolean, val folderPath: String, val grantedName: String?)

    private val resolver: ContentResolver get() = context.contentResolver
    private val prefs get() = context.getSharedPreferences("outbound", Context.MODE_PRIVATE)

    fun grantedTree(): Uri? {
        val s = prefs.getString(KEY_TREE, null) ?: return null
        val uri = Uri.parse(s)
        val ok = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return if (ok) uri else null
    }

    /** The scope of a picked tree, from its document id and authority; null when it must be refused. */
    fun levelOf(tree: Uri): GrantLevel? {
        if (tree.authority != EDEN_AUTHORITY) return null
        val id = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: IllegalArgumentException) { return null }
        return GrantScope.classify(id)
    }

    /**
     * Drops a stored grant that is broader than allowed (for example one kept by an older install):
     * releases the permission and forgets it. Returns true when something was dropped.
     */
    fun dropIfTooBroad(): Boolean {
        val s = prefs.getString(KEY_TREE, null) ?: return false
        val uri = Uri.parse(s)
        if (levelOf(uri) != null) return false
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Already gone; nothing left to release.
        }
        forgetGrant()
        return true
    }

    fun forgetGrant() {
        prefs.edit().remove(KEY_TREE).apply()
    }

    /** Intent for the folder picker: read-only, persistable. */
    fun buildPickerIntent(): Intent {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        // Open the picker at nand/user/save/0000000000000000 (document id "root/<relative path>", authority
        // verified in Eden's DocumentProvider.kt). The profile folder below it differs per user, so the hint stops there.
        // If the provider does not know that id the picker falls back on its own.
        val docId = EDEN_ROOT_ID + "/" + PATH.take(PROFILE_INDEX).joinToString("/")
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(EDEN_AUTHORITY, docId))
        return i
    }

    /** Persist ONLY the read permission, and only for an acceptable folder (see [GrantScope]). */
    fun persistGrant(uri: Uri) {
        if (levelOf(uri) == null) throw SafException(GrantScope.TOO_BROAD_MESSAGE)
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
    }

    fun listSavegames(tree: Uri): Listing {
        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(tree)
            val grantedName = queryName(tree, rootDocId)
            // The grant is PATH[k-1] (k = 6 title folder, 7 persistentDataPath, 8 Savegames); never an ancestor of the title folder.
            val k = TITLE_INDEX + 1 + (levelOf(tree)?.ordinal ?: throw SafException(GrantScope.TOO_BROAD_MESSAGE))
            var docId: String? = rootDocId
            for (seg in PATH.drop(k)) {
                docId = docId?.let { findSegment(tree, it, seg) }
                if (docId == null) break
            }
            if (docId != null) return listFolder(tree, docId, PATH.drop(k).joinToString("/") { it ?: "<your profile folder>" }.ifEmpty { "(granted folder)" }, grantedName)
            throw SafException("Savegames folder not found inside the granted folder")
        } catch (e: SecurityException) {
            throw SafException("Access to the granted folder was lost", e)
        } catch (e: IllegalArgumentException) {
            throw SafException("Granted folder is not usable", e)
        }
    }

    sealed class LiveRef {
        data class Found(val uri: Uri) : LiveRef()
        object NotReachable : LiveRef()
        object NotFound : LiveRef()
    }

    /**
     * Locates livepos.bin: a direct child of persistentDataPath, reachable when the grant is persistentDataPath
     * or the title folder. A grant on Savegames cannot reach it (no parent traversal). Lists folders; call rarely.
     */
    fun resolveLive(tree: Uri): LiveRef {
        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(tree)
            val level = levelOf(tree) ?: throw SafException(GrantScope.TOO_BROAD_MESSAGE)
            if (level == GrantLevel.SAVEGAMES) return LiveRef.NotReachable
            val k = TITLE_INDEX + 1 + level.ordinal // 6 title folder, 7 persistentDataPath
            var docId: String? = rootDocId
            for (seg in PATH.subList(k, PERSISTENT_INDEX + 1)) {
                docId = docId?.let { findSegment(tree, it, seg) }
                if (docId == null) break
            }
            if (docId != null) {
                var fileId: String? = null
                children(tree, docId) { id, n, mime, _, _ ->
                    if (fileId == null && mime != DocumentsContract.Document.MIME_TYPE_DIR && n == LIVE_FILE) fileId = id
                }
                return fileId?.let { LiveRef.Found(DocumentsContract.buildDocumentUriUsingTree(tree, it)) } ?: LiveRef.NotFound
            }
            return LiveRef.NotFound
        } catch (e: SecurityException) {
            throw SafException("Access to the granted folder was lost", e)
        } catch (e: IllegalArgumentException) {
            throw SafException("Granted folder is not usable", e)
        }
    }

    /** One read-only read of at most 16 bytes (the file is 16 bytes; the first 12 matter). */
    fun readLive(uri: Uri): ByteArray {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw SafException("Could not open livepos.bin")
        pfd.use {
            return FileInputStream(it.fileDescriptor).use { s ->
                val buf = ByteArray(16)
                val n = s.read(buf, 0, buf.size)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
        }
    }

    /** One cheap metadata query: the provider's lastModified for [uri], or null when it is not reported. */
    fun liveModified(uri: Uri): Long? {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0).takeIf { v -> v > 0 }
        }
        return null
    }

    /** Reads at most MAX_BYTES; a negative/unknown statSize is allowed because the stream read is hard-capped too. */
    fun read(entry: Entry): ByteArray {
        val pfd = resolver.openFileDescriptor(entry.docUri, "r") ?: throw SafException("Could not open save file")
        pfd.use {
            val size = it.statSize
            if (size > MAX_BYTES) throw SafException("Save file is unexpectedly large")
            return FileInputStream(it.fileDescriptor).use { s -> readCapped(s) }
        }
    }

    private fun readCapped(s: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw SafException("Save file is unexpectedly large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun queryName(tree: Uri, docId: String): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    private fun children(tree: Uri, parentDocId: String, block: (docId: String, name: String, mime: String, size: Long, modified: Long?) -> Unit) {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val modified = if (c.isNull(4)) null else c.getLong(4)
                block(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", if (c.isNull(3)) 0L else c.getLong(3), modified)
            }
        } ?: throw SafException("Folder listing returned no cursor")
    }

    /** A null [seg] is the per-user profile folder: the child folder that contains the game's title folder. */
    private fun findSegment(tree: Uri, parentDocId: String, seg: String?): String? {
        if (seg != null) return findChild(tree, parentDocId, seg)
        val dirs = ArrayList<String>()
        children(tree, parentDocId) { id, _, mime, _, _ ->
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) dirs += id
        }
        return dirs.firstOrNull { findChild(tree, it, TITLE_ID) != null }
    }

    private fun findChild(tree: Uri, parentDocId: String, name: String): String? {
        var found: String? = null
        children(tree, parentDocId) { id, n, mime, _, _ ->
            if (found == null && mime == DocumentsContract.Document.MIME_TYPE_DIR && n == name) found = id
        }
        return found
    }

    private fun listFolder(tree: Uri, docId: String, path: String, grantedName: String?): Listing {
        val out = ArrayList<Entry>()
        children(tree, docId) { id, name, mime, size, modified ->
            if (mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                out += Entry(Candidate(name, modified, size), DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        }
        val saves = out.filter { outbound.core.NewestSelector.isSaveName(it.candidate.name) }
        return Listing(out, saves.isNotEmpty() && saves.all { (it.candidate.lastModified ?: 0L) > 0L }, path, grantedName)
    }

    companion object {
        const val EDEN_AUTHORITY = "dev.eden.eden_emulator.user"
        const val EDEN_ROOT_ID = "root"
        const val LEAST_PRIVILEGE_NAME = "Savegames"
        const val RECOMMENDED_NAME = "persistentDataPath"
        const val LIVE_FILE = "livepos.bin"
        private const val KEY_TREE = "tree_uri"
        private const val MAX_BYTES = 8L * 1024 * 1024
        const val TITLE_ID = GrantScope.TITLE_ID
        /** Path below Eden's data root; null = the per-user profile folder (name differs per user). */
        val PATH: List<String?> = listOf(
            "nand", "user", "save", "0000000000000000", null,
            TITLE_ID, "persistentDataPath", "Savegames",
        )
        private val PROFILE_INDEX = PATH.indexOf(null)
        private val TITLE_INDEX = PATH.indexOf(TITLE_ID)
        private val PERSISTENT_INDEX = PATH.indexOf(RECOMMENDED_NAME)
    }
}
