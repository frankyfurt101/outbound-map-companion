package dev.local.outboundcompanion

import outbound.core.Candidate
import outbound.core.NewestSelector
import outbound.core.SaveDecodeException
import outbound.core.SaveDecoder
import outbound.core.SaveFallback
import outbound.core.SaveModel
import outbound.core.SaveState
import outbound.core.Selection

/** Messages are fixed strings: never include decoded/decrypted content or inner exception messages. */
class LoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class Loaded(
    val state: SaveState,
    val fileName: String,
    val modified: Long?,
    val size: Long,
    val selectionPath: String,
    val signature: String,
    val warning: String = "",
)

/** Chooses the newest readable save and decodes it. Call off the main thread. */
class SaveLoader(private val source: SafSaveSource, private val key: ByteArray) {

    /**
     * Cheap change detector. In the no-modified-time fallback mode a unique value is returned so
     * every poll re-reads (rare path).
     */
    fun signature(listing: SafSaveSource.Listing): String =
        when (val sel = NewestSelector.select(listing.entries.map { it.candidate })) {
            is Selection.None -> "none"
            is Selection.Newest -> sel.candidate.let { "${it.name}|${it.lastModified}|${it.size}" }
            is Selection.NeedsTimePlayedFallback -> "fallback-${System.nanoTime()}"
        }

    /** A SecurityException from the provider propagates (the UI then asks for a new grant). */
    fun load(listing: SafSaveSource.Listing): Loaded {
        val cands = listing.entries.map { it.candidate }
        val sel = NewestSelector.select(cands)
        if (sel is Selection.None) throw LoadException("No .save/.autosave files in the folder")
        val sig = signature(listing)
        val saves = cands.filter { NewestSelector.isSaveName(it.name) }

        fun attempt(path: String): (Candidate) -> Loaded? = { c ->
            val e = listing.entries.first { it.candidate == c }
            try {
                decode(e, path, sig)
            } catch (se: SecurityException) {
                throw se
            } catch (_: Exception) {
                null
            }
        }

        return if (sel is Selection.Newest) {
            val pick = SaveFallback.newestReadable(saves, attempt("newest by lastModified"))
                ?: throw LoadException("No save file could be decoded")
            if (pick.skippedUnreadable) pick.value.copy(warning = "A newer save file could not be read; showing ${pick.candidate.name}") else pick.value
        } else {
            val pick = SaveFallback.highestTimePlayed(saves, { l: Loaded -> l.state.timePlayedSec }, attempt("fallback: highest TimePlayed"))
                ?: throw LoadException("No save file could be decoded")
            if (pick.skippedUnreadable) pick.value.copy(warning = "A save file could not be read; showing ${pick.candidate.name} (highest play time)") else pick.value
        }
    }

    private fun decode(e: SafSaveSource.Entry, path: String, sig: String): Loaded {
        try {
            val json = SaveDecoder.decode(source.read(e), key)
            val state = SaveModel.parse(json)
            return Loaded(state, e.candidate.name, e.candidate.lastModified, e.candidate.size, path, sig)
        } catch (ex: SaveDecodeException) {
            throw LoadException("Decrypt failed", ex)
        } catch (ex: outbound.core.SaveParseException) {
            throw LoadException("Parse failed", ex)
        }
    }
}
