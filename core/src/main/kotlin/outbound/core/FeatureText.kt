package outbound.core

/** Display text for map features. */
object FeatureText {
    /** "pacificCoast-signal-tower-lighthouse" -> "Lighthouse" (region and kind prefix removed, then prettified). */
    fun name(f: Feature): String {
        var rest = f.id
        if (rest.startsWith(f.region + "-")) rest = rest.removePrefix(f.region + "-")
        if (rest.startsWith(f.kind.slug + "-")) rest = rest.removePrefix(f.kind.slug + "-")
        return Pretty.destination(rest).ifEmpty { Pretty.destination(f.id) }
    }

    /** "Signal tower · Lighthouse". */
    fun title(f: Feature): String = f.kind.label + " · " + name(f)

    /** ["scrap-metal:2", "bolts:1"] -> "Scrap Metal ×2, Bolts ×1"; entries without a count show the name only. */
    fun contents(raw: List<String>): String =
        StorageParser.parseAll(raw).joinToString(", ") { e ->
            val n = StorageParser.prettyName(e.id)
            if (e.count != null) "$n ×${e.count}" else n
        }
}
