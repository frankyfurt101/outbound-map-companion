package outbound.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class Confidence(val id: String) {
    HIGH("high"), MEDIUM("medium"), LOW("low");

    /** Plain words for the detail card. */
    val label: String get() = when (this) {
        HIGH -> "Well sourced"
        MEDIUM -> "One source"
        LOW -> "Unconfirmed: sources disagree or are weak"
    }

    companion object {
        /** Missing or unknown values count as LOW (the safe reading). */
        fun parse(s: String?): Confidence = entries.firstOrNull { it.id == s?.trim()?.lowercase() } ?: LOW
    }
}

data class GeneralGuide(val title: String, val text: String, val sources: List<String>, val confidence: Confidence)

data class RegionGuide(
    val regionId: String, val displayName: String, val overview: String,
    val tips: List<String>, val sources: List<String>, val confidence: Confidence,
)

data class PlaceGuide(
    val poiId: String?, val region: String, val playerName: String, val what: String,
    val todo: List<String>, val rewards: List<String>, val requirements: List<String>, val tips: List<String>,
    val sources: List<String>, val confidence: Confidence, val note: String,
)

class GuideTableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Guide notes from `guides.json`. Every field except the top-level shape is optional. */
class GuideTable(val generated: String, val general: List<GeneralGuide>, val regions: List<RegionGuide>, val places: List<PlaceGuide>) {
    private val regionById = regions.associateBy { it.regionId }
    private val placeByPoi = places.filter { !it.poiId.isNullOrBlank() }.associateBy { it.poiId!! }
    private val placesByRegion = places.groupBy { it.region }

    fun region(id: String): RegionGuide? = regionById[id]
    fun placesIn(regionId: String): List<PlaceGuide> = placesByRegion[regionId] ?: emptyList()
    fun placeFor(poiId: String?): PlaceGuide? = if (poiId.isNullOrBlank()) null else placeByPoi[poiId]

    companion object {
        const val RESOURCE = "guides.json"
        private val json = Json { isLenient = true }

        /** "https://www.trueachievements.com/game/x" -> "trueachievements.com"; unparseable input is returned trimmed. */
        fun host(url: String): String {
            val t = url.trim()
            val rest = t.substringAfter("://", t)
            val h = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            return h.removePrefix("www.").ifEmpty { t }
        }

        fun parse(text: String): GuideTable {
            val root = try {
                json.parseToJsonElement(text) as? JsonObject ?: throw GuideTableException("guides.json root is not an object")
            } catch (e: SerializationException) {
                throw GuideTableException("guides.json could not be parsed", e)
            } catch (e: IllegalArgumentException) {
                throw GuideTableException("guides.json could not be parsed", e)
            }
            val general = (root["general"] as? JsonArray).objs().mapNotNull { o ->
                val title = o.s("title") ?: return@mapNotNull null
                GeneralGuide(title, o.s("text") ?: "", o.list("sources"), Confidence.parse(o.s("confidence")))
            }
            val regions = (root["regions"] as? JsonObject)?.mapNotNull { (id, el) ->
                val o = el as? JsonObject ?: return@mapNotNull null
                RegionGuide(
                    id, o.s("displayName") ?: Region.fromId(id)?.label ?: id, o.s("overview") ?: "",
                    o.list("tips"), o.list("sources"), Confidence.parse(o.s("confidence")),
                )
            } ?: emptyList()
            val places = (root["places"] as? JsonArray).objs().mapNotNull { o ->
                val region = o.s("region") ?: return@mapNotNull null
                val poiId = o.s("poiId")?.takeIf { it.isNotBlank() }
                val name = o.s("playerName") ?: poiId?.let { Pretty.destination(it) } ?: return@mapNotNull null
                PlaceGuide(
                    poiId, region, name, o.s("what") ?: "", o.list("todo"), o.list("rewards"), o.list("requirements"),
                    o.list("tips"), o.list("sources"), Confidence.parse(o.s("confidence")), o.s("note") ?: "",
                )
            }
            return GuideTable(root.s("generated") ?: "", general, regions, places)
        }

        fun loadResource(): GuideTable {
            val stream = GuideTable::class.java.classLoader?.getResourceAsStream(RESOURCE)
                ?: throw GuideTableException("$RESOURCE not found on the classpath")
            return parse(stream.use { String(it.readBytes(), Charsets.UTF_8) })
        }

        val EMPTY = GuideTable("", emptyList(), emptyList(), emptyList())

        private fun JsonArray?.objs(): List<JsonObject> = this?.mapNotNull { it as? JsonObject } ?: emptyList()
        private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.list(k: String): List<String> =
            (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } } ?: emptyList()
    }
}
