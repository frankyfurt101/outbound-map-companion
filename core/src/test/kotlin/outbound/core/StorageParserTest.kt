package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageParserTest {
    @Test fun normal() {
        val e = StorageParser.parse("lumber-1:7163")
        assertEquals("lumber-1", e.id); assertEquals(7163L, e.count)
    }

    @Test fun missingColon() {
        val e = StorageParser.parse("wrench-1")
        assertEquals("wrench-1", e.id); assertNull(e.count)
    }

    @Test fun nonNumeric() {
        val e = StorageParser.parse("door-right:True")
        assertEquals("door-right", e.id); assertNull(e.count)
    }

    @Test fun decimalAndEmpty() {
        assertEquals(5L, StorageParser.parse("x:5.0").count)
        assertEquals("", StorageParser.parse("").id)
        assertNull(StorageParser.parse("x:").count)
        assertEquals("a:b", StorageParser.parse("a:b:3").id)
    }

    @Test fun pretty() {
        assertEquals("Lumber 1", StorageParser.prettyName("lumber-1"))
        assertEquals("Bottle Cap", StorageParser.prettyName("bottle-cap"))
        assertEquals("", StorageParser.prettyName(""))
    }
}
