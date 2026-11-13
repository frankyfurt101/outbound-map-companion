package outbound.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** A named world location. [region] is a [Region.id]; x/z are world coordinates (y = height, unused on the map). */
data class Poi(val id: String, val region: String, val name: String, val x: Double, val y: Double, val z: Double)

class PoiTableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Points of interest from `pois.json` (base game build; ids from a newer build may be missing). */
class PoiTable(val all: List<Poi>) {
    private val byId = all.associateBy { it.id }
    private val byRegion = all.groupBy { it.region }

    val size: Int get() = all.size

    /** Null when the id is blank or not in the table. */
    fun find(id: String?): Poi? = if (id.isNullOrBlank()) null else byId[id]

    fun inRegion(regionId: String): List<Poi> = byRegion[regionId] ?: emptyList()

    companion object {
        const val RESOURCE = "pois.json"
        private val json = Json { isLenient = true }

        fun parse(text: String): PoiTable {
            val arr = try {
                json.parseToJsonElement(text) as? JsonArray ?: throw PoiTableException("pois.json root is not an array")
            } catch (e: SerializationException) {
                throw PoiTableException("pois.json could not be parsed", e)
            } catch (e: IllegalArgumentException) {
                throw PoiTableException("pois.json could not be parsed", e)
            }
            val list = arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                val id = s("id") ?: return@mapNotNull null
                val x = d("x") ?: return@mapNotNull null
                val z = d("z") ?: return@mapNotNull null
                Poi(id, s("region") ?: "", s("name") ?: Pretty.destination(id), x, d("y") ?: 0.0, z)
            }
            return PoiTable(list)
        }

        /** Loads the classpath resource (JVM tests). The app loads the same file from its assets. */
        fun loadResource(): PoiTable {
            val stream = PoiTable::class.java.classLoader?.getResourceAsStream(RESOURCE)
                ?: throw PoiTableException("$RESOURCE not found on the classpath")
            return parse(stream.use { String(it.readBytes(), Charsets.UTF_8) })
        }

        val EMPTY = PoiTable(emptyList())
    }
}
