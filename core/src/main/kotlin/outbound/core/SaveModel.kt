package outbound.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

class SaveParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class Vec3(val x: Double, val y: Double, val z: Double)

/** The parts of the save this app uses. Everything else is ignored. */
data class SaveState(
    val levelScene: String,
    val player: Vec3?,
    val vehicle: Vec3?,
    val trackedPoi: String,
    val timePlayedSec: Long,
    val saveDateTime: Long?,
    val currentSignal: String,
    val playerStorage: List<String>,
    val playerTools: List<String>,
    /** Raw JSON text of Player.PlayerBackpacks ("[]" when empty/missing); shape is unknown. */
    val playerBackpacksRaw: String,
    val vehicleStorage: List<String>,
    /** UsedSignalTowers: ids of towers already used (empty when missing). */
    val usedSignalTowers: Set<String> = emptySet(),
    /** Ids of campfires with IsInteracted = true in World.WorldInteractions (empty when missing). */
    val interactedCampfires: Set<String> = emptySet(),
    /** World.SupplyCaches: cache id -> CurrentStorage entries ("item:count"); a cache that is not listed is not a key. */
    val supplyCaches: Map<String, List<String>> = emptyMap(),
) {
    val backpacksNonEmpty: Boolean get() = playerBackpacksRaw.replace(" ", "") !in setOf("[]", "")
}

object SaveModel {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): SaveState {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw SaveParseException("Save JSON could not be parsed", e)
        } catch (e: IllegalArgumentException) {
            throw SaveParseException("Save JSON could not be parsed", e)
        }
        val obj = root as? JsonObject ?: throw SaveParseException("Save JSON root is not an object")
        val level = obj.str("CurrentLevelScene") ?: throw SaveParseException("CurrentLevelScene missing")
        val player = obj["Player"] as? JsonObject ?: throw SaveParseException("Player section missing")
        val vehicle = obj["Vehicle"] as? JsonObject
        val world = obj["World"] as? JsonObject
        return SaveState(
            levelScene = level,
            player = player["PlayerPosition"].vec3(),
            vehicle = vehicle?.get("VehiclePosition").vec3(),
            trackedPoi = obj.str("TrackedPoi") ?: "",
            timePlayedSec = obj["TimePlayed"].num()?.toLong() ?: 0L,
            saveDateTime = obj["SaveDateTime"].num()?.toLong(),
            currentSignal = obj.str("CurrentSignal") ?: "",
            playerStorage = player["PlayerStorage"].strings(),
            playerTools = player["PlayerTools"].strings(),
            playerBackpacksRaw = (player["PlayerBackpacks"] ?: JsonArray(emptyList())).toString(),
            vehicleStorage = vehicle?.get("VehicleStorage").strings(),
            usedSignalTowers = obj["UsedSignalTowers"].strings().toSet(),
            interactedCampfires = world?.get("WorldInteractions").interactedCampfires(),
            supplyCaches = world?.get("SupplyCaches").caches(),
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.longOrNull?.toDouble() }

    private fun JsonElement?.strings(): List<String> =
        (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    private fun JsonElement?.interactedCampfires(): Set<String> {
        val out = HashSet<String>()
        for (el in this as? JsonArray ?: return out) {
            val o = el as? JsonObject ?: continue
            val id = o.str("ID") ?: continue
            if (!id.contains("-camp-fire-")) continue
            val flag = o["IsInteracted"] as? JsonPrimitive ?: continue
            if (flag.contentOrNull == "true") out += id
        }
        return out
    }

    private fun JsonElement?.caches(): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (el in this as? JsonArray ?: return out) {
            val o = el as? JsonObject ?: continue
            val id = o.str("ID") ?: continue
            out[id] = o["CurrentStorage"].strings()
        }
        return out
    }

    private fun JsonElement?.vec3(): Vec3? {
        val o = this as? JsonObject ?: return null
        val x = o["x"].num() ?: return null
        val y = o["y"].num() ?: 0.0
        val z = o["z"].num() ?: return null
        return Vec3(x, y, z)
    }
}

/** The four game regions: save id, bundled asset file name, label. */
enum class Region(val id: String, val assetFile: String, val label: String) {
    PACIFIC_COAST("pacificCoast", "map-pacific-coast.png", "Pacific Coast"),
    STARTING_LEVEL("startingLevel", "map-starting-level.png", "Starting Level"),
    CANYONS("canyons", "map-canyons.png", "Canyons"),
    MOUNTAINS("mountains", "map-mountains.png", "Mountains");

    companion object {
        fun fromId(id: String): Region? = entries.firstOrNull { it.id == id }
    }
}
