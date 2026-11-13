package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SaveModelTest {
    @Test fun minimalAndTolerant() {
        val s = SaveModel.parse(
            """{"CurrentLevelScene":"canyons","Unknown":[1,2],"TimePlayed":3600,"TrackedPoi":"",
               "Player":{"PlayerPosition":{"x":1,"y":2,"z":3},"PlayerStorage":["a-b:2"],"PlayerBackpacks":[]},
               "Vehicle":{"VehiclePosition":{"x":4,"y":5,"z":6},"VehicleStorage":["lumber-1:7"]}}""",
        )
        assertEquals("canyons", s.levelScene)
        assertEquals(Vec3(1.0, 2.0, 3.0), s.player)
        assertEquals(Vec3(4.0, 5.0, 6.0), s.vehicle)
        assertEquals(3600L, s.timePlayedSec)
        assertEquals("", s.trackedPoi)
        assertFalse(s.backpacksNonEmpty)
        assertEquals(listOf("lumber-1:7"), s.vehicleStorage)
    }

    @Test fun nonEmptyBackpacksKeptRaw() {
        val s = SaveModel.parse("""{"CurrentLevelScene":"x","Player":{"PlayerBackpacks":[{"k":1}]}}""")
        assertTrue(s.backpacksNonEmpty)
        assertEquals("""[{"k":1}]""", s.playerBackpacksRaw)
    }

    @Test fun invalidInputsFail() {
        for (bad in listOf("", "[]", "{", """{"Player":{}}""", """{"CurrentLevelScene":"x"}""")) {
            try { SaveModel.parse(bad); fail("expected failure for input of length ${bad.length}") } catch (_: SaveParseException) {}
        }
    }

    @Test fun parsesRealSampleJson() {
        val f = TestSupport.requireFile("OUTBOUND_SAMPLE_JSON")
        val s = SaveModel.parse(f.readText())
        assertNotNull(Region.fromId(s.levelScene))
        assertNotNull(s.player); assertNotNull(s.vehicle)
        assertTrue(s.timePlayedSec > 0)
        assertTrue(s.vehicleStorage.isNotEmpty())
        assertTrue(s.playerStorage.isNotEmpty())
    }

    @Test fun decryptsRealSampleSave() {
        val key = TestSupport.requireFile("OUTBOUND_KEY_FILE").readBytes()
        val save = TestSupport.requireFile("OUTBOUND_SAMPLE_SAVE").readBytes()
        val decoded = SaveDecoder.decode(save, key)
        val parsed = SaveModel.parse(decoded)
        // Compare structurally with the pre-decrypted sample when available.
        val jsonFile = System.getenv("OUTBOUND_SAMPLE_JSON")?.let { java.io.File(it) }
        if (jsonFile != null && jsonFile.isFile) assertEquals(SaveModel.parse(jsonFile.readText()), parsed)
        assertNotNull(Region.fromId(parsed.levelScene))
    }
}

class SaveFeatureFieldsTest {
    private fun parse(extra: String) = SaveModel.parse("""{"CurrentLevelScene":"pacificCoast","Player":{}$extra}""")

    @Test fun missingFieldsGiveEmpty() {
        val s = parse("")
        assertTrue(s.usedSignalTowers.isEmpty())
        assertTrue(s.interactedCampfires.isEmpty())
        assertTrue(s.supplyCaches.isEmpty())
    }

    @Test fun wrongShapesAreTolerated() {
        val s = parse(""","UsedSignalTowers":"x","World":{"WorldInteractions":{"a":1},"SupplyCaches":[1,{"CurrentStorage":["a:1"]},{"ID":"c"}]}""")
        assertTrue(s.usedSignalTowers.isEmpty())
        assertTrue(s.interactedCampfires.isEmpty())
        assertEquals(mapOf("c" to emptyList<String>()), s.supplyCaches)
        assertTrue(parse(""","World":[]""").supplyCaches.isEmpty())
    }

    @Test fun parsesInlineFields() {
        val s = parse(
            ""","UsedSignalTowers":["pacificCoast-signal-tower-lighthouse"],
               "World":{"WorldInteractions":[
                  {"ID":"pacificCoast-camp-fire-first-beach","IsInteracted":true},
                  {"ID":"pacificCoast-camp-fire-other","IsInteracted":false},
                  {"ID":"pacificCoast-camp-fire-nofield"},
                  {"ID":"pacificCoast-orange-houseboat-door-1","IsInteracted":true}],
                "SupplyCaches":[{"ID":"pacificCoast-supply-cache-flower-fields","CurrentStorage":["scrap-metal:2","bolts:1"]},
                  {"ID":"pacificCoast-supply-cache-cave","CurrentStorage":[]},{"ID":"pacificCoast-supply-cache-nostore"}]}""",
        )
        assertEquals(setOf("pacificCoast-signal-tower-lighthouse"), s.usedSignalTowers)
        assertEquals(setOf("pacificCoast-camp-fire-first-beach"), s.interactedCampfires)
        assertEquals(listOf("scrap-metal:2", "bolts:1"), s.supplyCaches["pacificCoast-supply-cache-flower-fields"])
        assertEquals(emptyList<String>(), s.supplyCaches["pacificCoast-supply-cache-cave"])
        assertEquals(emptyList<String>(), s.supplyCaches["pacificCoast-supply-cache-nostore"])
    }

    @Test fun realSampleHasFeatureFields() {
        val s = SaveModel.parse(TestSupport.requireFile("OUTBOUND_SAMPLE_JSON").readText())
        assertTrue(s.usedSignalTowers.isNotEmpty())
        assertTrue(s.supplyCaches.isNotEmpty())
        assertTrue(s.supplyCaches.values.any { it.isNotEmpty() })
        assertTrue(s.interactedCampfires.isNotEmpty())
        assertTrue(s.interactedCampfires.all { it.contains("-camp-fire-") })
    }
}
