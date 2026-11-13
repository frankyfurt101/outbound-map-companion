package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTableTest {
    private val table = GuideTable.loadResource()

    @Test fun counts() {
        assertEquals(18, table.general.size)
        assertEquals(4, table.regions.size)
        assertEquals(43, table.places.size)
    }

    @Test fun everyPoiIdExistsInPoiTable() {
        val pois = PoiTable.loadResource()
        for (p in table.places) if (p.poiId != null) assertNotNull("poiId ${p.poiId}", pois.find(p.poiId))
    }

    @Test fun everyRegionIdMapsToARegion() {
        for (r in table.regions) assertNotNull(r.regionId, Region.fromId(r.regionId))
        for (p in table.places) assertNotNull("place region ${p.region}", Region.fromId(p.region))
        for (r in Region.entries) assertNotNull(r.id, table.region(r.id))
    }

    @Test fun lighthouseIsKnown() {
        val p = table.placeFor("lighthouse")
        assertNotNull(p)
        assertEquals("pacificCoast", p!!.region)
        assertTrue(p.todo.isNotEmpty())
        assertTrue(table.placesIn("pacificCoast").contains(p))
        assertNull(table.placeFor("no-such"))
        assertNull(table.placeFor(null))
    }

    @Test fun missingOptionalFieldsAreTolerated() {
        val t = GuideTable.parse("""{"places":[{"region":"canyons","playerName":"X"},{"poiId":"a-b","region":"canyons"},{"nope":1}],
            "general":[{"title":"T"}],"regions":{"canyons":{}}}""")
        assertEquals(2, t.places.size)
        val x = t.places[0]
        assertNull(x.poiId); assertTrue(x.todo.isEmpty()); assertEquals(Confidence.LOW, x.confidence)
        assertEquals("A B", t.places[1].playerName)
        assertEquals("T", t.general[0].title)
        assertEquals("Canyons", t.region("canyons")!!.displayName)
    }

    @Test fun badJsonThrowsUsefulError() {
        for (bad in listOf("{", "[]", "")) {
            try { GuideTable.parse(bad); throw AssertionError("expected exception for '$bad'") }
            catch (e: GuideTableException) { assertTrue(e.message!!.contains("guides.json")) }
        }
    }

    @Test fun hostsAndConfidenceWords() {
        assertEquals("trueachievements.com", GuideTable.host("https://www.trueachievements.com/game/Outbound/walkthrough/4"))
        assertEquals("wikily.gg", GuideTable.host("wikily.gg/x"))
        assertEquals("Well sourced", Confidence.parse("high").label)
        assertEquals("One source", Confidence.parse("medium").label)
        assertTrue(Confidence.parse("weird").label.startsWith("Unconfirmed"))
    }
}
