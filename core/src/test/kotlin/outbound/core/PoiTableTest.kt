package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiTableTest {
    private val table = PoiTable.loadResource()

    @Test fun loads51Entries() = assertEquals(51, table.size)

    @Test fun bixbyBridgeIsInPacificCoast() {
        val p = table.find("bixby-bridge")
        assertNotNull(p)
        assertEquals("pacificCoast", p!!.region)
        assertEquals(1261.5, p.x, 0.5)
        assertEquals(1028.4, p.z, 0.5)
        assertTrue(table.inRegion("pacificCoast").contains(p))
    }

    @Test fun unknownOrBlankIdIsNull() {
        assertNull(table.find("no-such-place"))
        assertNull(table.find(""))
        assertNull(table.find(null))
    }

    @Test fun everyRegionIdMapsToARegion() {
        for (p in table.all) assertNotNull("region of ${p.id}: ${p.region}", Region.fromId(p.region))
    }

    @Test fun everyRegionHasEntriesAndIdsAreUnique() {
        for (r in Region.entries) assertTrue(r.id, table.inRegion(r.id).isNotEmpty())
        assertEquals(table.size, table.all.map { it.id }.toSet().size)
    }

    @Test fun badJsonThrowsUsefulError() {
        try { PoiTable.parse("{}"); throw AssertionError("expected exception") } catch (e: PoiTableException) { /* ok */ }
        assertEquals(1, PoiTable.parse("""[{"id":"a","x":1,"z":2},{"nope":1}]""").size)
    }
}
