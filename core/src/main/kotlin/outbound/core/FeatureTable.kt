package outbound.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Map feature kinds in `features.json`: [json] is the value of the `kind` field, [slug] the kind part of the save id. */
enum class FeatureKind(val json: String, val slug: String, val label: String) {
    CAMPFIRE("campfire", "camp-fire", "Campfire"),
    TOWER("tower", "signal-tower", "Signal tower"),
    CACHE("cache", "supply-cache", "Supply cache");

    companion object {
        fun fromJson(s: String?): FeatureKind? = entries.firstOrNull { it.json == s }
    }
}

/** A campfire, signal tower or supply cache. [id] is the full save id; [region] is a [Region.id]; x/z are world coordinates. */
data class Feature(val id: String, val kind: FeatureKind, val region: String, val x: Double, val y: Double, val z: Double)

class FeatureTableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Map features from `features.json` (base game build; positions on a newer build are not verified). */
class FeatureTable(val all: List<Feature>) {
    private val byId = all.associateBy { it.id }
    private val byRegion = all.groupBy { it.region }

    val size: Int get() = all.size

    fun find(id: String?): Feature? = if (id.isNullOrBlank()) null else byId[id]

    fun inRegion(regionId: String): List<Feature> = byRegion[regionId] ?: emptyList()

    fun inRegion(regionId: String, kind: FeatureKind): List<Feature> = inRegion(regionId).filter { it.kind == kind }

    fun count(kind: FeatureKind): Int = all.count { it.kind == kind }

    companion object {
        const val RESOURCE = "features.json"
        private val json = Json { isLenient = true }

        fun parse(text: String): FeatureTable {
            val arr = try {
                json.parseToJsonElement(text) as? JsonArray ?: throw FeatureTableException("features.json root is not an array")
            } catch (e: SerializationException) {
                throw FeatureTableException("features.json could not be parsed", e)
            } catch (e: IllegalArgumentException) {
                throw FeatureTableException("features.json could not be parsed", e)
            }
            val list = arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                val id = s("id") ?: return@mapNotNull null
                val kind = FeatureKind.fromJson(s("kind")) ?: return@mapNotNull null
                val x = d("x") ?: return@mapNotNull null
                val z = d("z") ?: return@mapNotNull null
                Feature(id, kind, s("region") ?: "", x, d("y") ?: 0.0, z)
            }
            return FeatureTable(list)
        }

        /** Loads the classpath resource (JVM tests). The app loads the same file from its assets. */
        fun loadResource(): FeatureTable {
            val stream = FeatureTable::class.java.classLoader?.getResourceAsStream(RESOURCE)
                ?: throw FeatureTableException("$RESOURCE not found on the classpath")
            return parse(stream.use { String(it.readBytes(), Charsets.UTF_8) })
        }

        val EMPTY = FeatureTable(emptyList())
    }
}
