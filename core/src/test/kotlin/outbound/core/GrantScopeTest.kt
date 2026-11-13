package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantScopeTest {
    private val base = "root/nand/user/save/0000000000000000"
    private val title = "0100ED9024EB8000"

    @Test fun acceptsTheThreeFoldersUnderAnyProfile() {
        for (profile in listOf("A1B2C3D4E5F60718293A4B5C6D7E8F90", "0000000000000000", "x y", "p")) {
            assertEquals(GrantLevel.TITLE, GrantScope.classify("$base/$profile/$title"))
            assertEquals(GrantLevel.PERSISTENT, GrantScope.classify("$base/$profile/$title/persistentDataPath"))
            assertEquals(GrantLevel.SAVEGAMES, GrantScope.classify("$base/$profile/$title/persistentDataPath/Savegames"))
        }
    }

    @Test fun acceptsWithoutRootPrefix() {
        assertTrue(GrantScope.isAcceptable("nand/user/save/0000000000000000/P/$title/persistentDataPath"))
    }

    @Test fun rejectsBroaderFolders() {
        for (id in listOf(
            "root", "", "root/nand", "root/nand/user", "root/nand/user/save", base, "$base/PROFILE",
        )) assertFalse(id, GrantScope.isAcceptable(id))
        assertNull(GrantScope.classify(null))
    }

    @Test fun rejectsUnrelatedAndLookAlikePaths() {
        for (id in listOf(
            "root/other/persistentDataPath",
            "persistentDataPath",
            "root/Savegames",
            "primary:Download/persistentDataPath",
            "root/nand/user/save/0000000000000000/P/OTHERTITLE/persistentDataPath",
            "root/nand/user/save/0000000000000000/P/$title/other",
            "root/nand/user/save/0000000000000000/P/$title/persistentDataPath/Other",
            "root/nand/user/save/0000000000000000/P/$title/persistentDataPath/Savegames/deeper",
            "root/nand/user/save/0000000000000001/P/$title/persistentDataPath",
            "root/nand/user/save/0000000000000000//$title/persistentDataPath",
            "root/backup/nand/user/save/0000000000000000/P/$title/persistentDataPath",
            "root/nand/user/save/0000000000000000/P/$title/persistentdatapath",
            "root/root/nand/user/save/0000000000000000/P/$title",
        )) assertFalse(id, GrantScope.isAcceptable(id))
    }
}
