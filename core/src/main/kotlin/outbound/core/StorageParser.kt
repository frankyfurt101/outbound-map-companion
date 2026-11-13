package outbound.core

/** One "id:count" entry. [count] is null when the entry has no usable number. */
data class StorageEntry(val id: String, val count: Long?, val raw: String)

object StorageParser {
    fun parse(raw: String): StorageEntry {
        val s = raw.trim()
        val i = s.lastIndexOf(':')
        if (i < 0) return StorageEntry(s, null, raw)
        val id = s.substring(0, i).trim()
        val n = s.substring(i + 1).trim()
        val count = n.toLongOrNull() ?: n.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
        return StorageEntry(id, count, raw)
    }

    fun parseAll(raw: List<String>): List<StorageEntry> = raw.map(::parse)

    /** "lumber-1" -> "Lumber 1". */
    fun prettyName(id: String): String =
        id.split('-', '_', ' ').filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
}
