package outbound.core

/** How deep inside the game's folder a picked folder is. Only these three are acceptable. */
enum class GrantLevel { TITLE, PERSISTENT, SAVEGAMES }

/**
 * Decides which picked folders the app may keep a read grant on. The decision is made from the
 * picked tree's document id (path segments), never from the display name alone, so a look-alike
 * folder elsewhere is refused. Anything above the game's title folder (profile folder, save, user,
 * nand, the emulator root) is refused: it would expose other games and the user's console keys.
 */
object GrantScope {
    const val ROOT_SEGMENT = "root"
    const val TITLE_ID = "0100ED9024EB8000"
    const val PERSISTENT = "persistentDataPath"
    const val SAVEGAMES = "Savegames"
    const val TOO_BROAD_MESSAGE =
        "That folder is too broad: it would expose other games and your console keys. Pick persistentDataPath."

    private val PREFIX = listOf("nand", "user", "save", "0000000000000000")

    /** The level of [docId] ("root/nand/user/save/0000000000000000/<profile>/<title>[/persistentDataPath[/Savegames]]"), or null if refused. */
    fun classify(docId: String?): GrantLevel? {
        if (docId == null) return null
        var segs = docId.split('/')
        if (segs.firstOrNull() == ROOT_SEGMENT) segs = segs.drop(1)
        if (segs.size < PREFIX.size + 2) return null
        if (segs.subList(0, PREFIX.size) != PREFIX) return null
        val profile = segs[PREFIX.size]
        if (profile.isEmpty()) return null
        if (segs[PREFIX.size + 1] != TITLE_ID) return null
        val rest = segs.drop(PREFIX.size + 2)
        return when {
            rest.isEmpty() -> GrantLevel.TITLE
            rest == listOf(PERSISTENT) -> GrantLevel.PERSISTENT
            rest == listOf(PERSISTENT, SAVEGAMES) -> GrantLevel.SAVEGAMES
            else -> null
        }
    }

    fun isAcceptable(docId: String?): Boolean = classify(docId) != null
}
