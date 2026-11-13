package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureTableTest {
    private val table = FeatureTable.loadResource()

    @Test fun loads190Entries() = assertEquals(190, table.size)

    @Test fun countsPerKind() {
        assertEquals(80, table.count(FeatureKind.CAMPFIRE))
        assertEquals(56, table.count(FeatureKind.TOWER))
        assertEquals(54, table.count(FeatureKind.CACHE))
    }

    @Test fun idsAreUniqueAndRegionsMap() {
        assertEquals(table.size, table.all.map { it.id }.toSet().size)
        for (f in table.all) assertNotNull("region of ${f.id}: ${f.region}", Region.fromId(f.region))
    }

    @Test fun idsStartWithRegionAndKindSlug() {
        for (f in table.all) assertTrue(f.id, f.id.startsWith(f.region + "-" + f.kind.slug + "-"))
    }

    @Test fun lighthouseTower() {
        val f = table.find("pacificCoast-signal-tower-lighthouse")
        assertNotNull(f)
        assertEquals(FeatureKind.TOWER, f!!.kind)
        assertEquals("pacificCoast", f.region)
        assertEquals(596.6, f.x, 0.5)
        assertEquals(693.4, f.z, 0.5)
        assertTrue(table.inRegion("pacificCoast", FeatureKind.TOWER).contains(f))
    }

    @Test fun regionListsPartitionTheTable() {
        assertEquals(table.size, Region.entries.sumOf { table.inRegion(it.id).size })
        assertEquals(table.inRegion("canyons").size, FeatureKind.entries.sumOf { table.inRegion("canyons", it).size })
    }

    @Test fun badJsonThrowsAndBadEntriesAreSkipped() {
        try { FeatureTable.parse("{}"); throw AssertionError("expected exception") } catch (e: FeatureTableException) { /* ok */ }
        val t = FeatureTable.parse("""[{"id":"a","kind":"tower","x":1,"z":2},{"id":"b","kind":"fish","x":1,"z":2},{"nope":1}]""")
        assertEquals(1, t.size)
    }

    @Test fun namesAndTitles() {
        assertEquals("Signal tower · Lighthouse", FeatureText.title(table.find("pacificCoast-signal-tower-lighthouse")!!))
        assertEquals("Campfire · First Beach", FeatureText.title(table.find("pacificCoast-camp-fire-first-beach")!!))
        assertEquals("Supply cache · Flower Fields", FeatureText.title(table.find("pacificCoast-supply-cache-flower-fields")!!))
        assertEquals("Center Plateau 2", FeatureText.name(table.find("startingLevel-camp-fire-center-plateau-2")!!))
    }

    @Test fun cacheContentsText() {
        assertEquals("Scrap Metal ×2, Sheet Metal ×1, Bolts ×1", FeatureText.contents(listOf("scrap-metal:2", "sheet-metal:1", "bolts:1")))
        assertEquals("Bolts", FeatureText.contents(listOf("bolts")))
        assertEquals("", FeatureText.contents(emptyList()))
    }
}
