package outbound.core

data class Candidate(val name: String, val lastModified: Long?, val size: Long)

sealed class Selection {
    object None : Selection()
    data class Newest(val candidate: Candidate) : Selection()
    /** Some lastModified is null/0: the caller must decrypt all and pick the highest TimePlayed. */
    data class NeedsTimePlayedFallback(val candidates: List<Candidate>) : Selection()
}

object NewestSelector {
    fun isSaveName(name: String): Boolean =
        name.endsWith(".save", ignoreCase = true) || name.endsWith(".autosave", ignoreCase = true)

    fun select(all: List<Candidate>): Selection {
        val c = all.filter { isSaveName(it.name) }
        if (c.isEmpty()) return Selection.None
        if (c.any { (it.lastModified ?: 0L) <= 0L }) return Selection.NeedsTimePlayedFallback(c)
        val best = c.maxWithOrNull(compareBy<Candidate> { it.lastModified }.thenBy { it.name })!!
        return Selection.Newest(best)
    }
}
