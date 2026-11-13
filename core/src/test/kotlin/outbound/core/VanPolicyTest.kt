package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VanPolicyTest {
    private val saveVan = Vec3(10.0, 1.0, 20.0)
    private fun input(
        live: Position? = null, fresh: Boolean = false, mem: VanMemory? = null,
        saveMod: Long? = 1000, region: String = "R", now: Long = 5000,
    ) = VanInput(region, saveVan, saveMod, live, fresh, mem, now)

    private val driving = Position(100f, 5f, 200f, 1)
    private val onFoot = Position(150f, 5f, 250f, 0)
    private val mem = VanMemory(100.0, 5.0, 200.0, "R", 3000)

    @Test fun noVehicleEverSeenUsesSave() {
        val d = VanPolicy.decide(input(live = onFoot, fresh = true))
        assertEquals(VanSource.SAVE, d.source); assertEquals(saveVan, d.position); assertNull(d.memory)
    }

    @Test fun freshInVehicleIsLiveAndRemembered() {
        val d = VanPolicy.decide(input(live = driving, fresh = true))
        assertEquals(VanSource.LIVE, d.source); assertEquals(Vec3(100.0, 5.0, 200.0), d.position)
        assertEquals(VanMemory(100.0, 5.0, 200.0, "R", 5000), d.memory)
    }

    @Test fun passengerSeatCountsToo() {
        val d = VanPolicy.decide(input(live = Position(1f, 2f, 3f, 6), fresh = true))
        assertEquals(VanSource.LIVE, d.source)
    }

    @Test fun staleInVehicleSampleDoesNotCreateMemory() {
        val d = VanPolicy.decide(input(live = driving, fresh = false))
        assertEquals(VanSource.SAVE, d.source); assertNull(d.memory)
    }

    @Test fun afterGettingOutVanStaysAtLastSeen() {
        val d = VanPolicy.decide(input(live = onFoot, fresh = true, mem = mem))
        assertEquals(VanSource.LAST_SEEN, d.source); assertEquals(Vec3(100.0, 5.0, 200.0), d.position)
        assertEquals(2000L, d.ageMs); assertEquals(mem, d.memory)
    }

    @Test fun staleLiveKeepsLastSeen() {
        val d = VanPolicy.decide(input(live = null, mem = mem))
        assertEquals(VanSource.LAST_SEEN, d.source)
    }

    @Test fun newerSaveWinsAndClearsMemory() {
        val d = VanPolicy.decide(input(live = onFoot, fresh = true, mem = mem, saveMod = 4000))
        assertEquals(VanSource.SAVE, d.source); assertEquals(saveVan, d.position); assertNull(d.memory)
    }

    @Test fun saveWithEqualTimeWins() {
        assertEquals(VanSource.SAVE, VanPolicy.decide(input(mem = mem, saveMod = 3000)).source)
    }

    @Test fun unknownSaveTimeKeepsMemory() {
        assertEquals(VanSource.LAST_SEEN, VanPolicy.decide(input(mem = mem, saveMod = null)).source)
    }

    @Test fun otherRegionMemoryIsDropped() {
        val d = VanPolicy.decide(input(mem = mem, region = "Other"))
        assertEquals(VanSource.SAVE, d.source); assertNull(d.memory)
    }

    @Test fun drivingAgainAfterNewerSaveRemembersAgain() {
        val d = VanPolicy.decide(input(live = driving, fresh = true, mem = mem, saveMod = 4000))
        assertEquals(VanSource.LIVE, d.source); assertEquals(5000L, d.memory!!.wallMs)
    }

    @Test fun nullSaveVanWithoutMemory() {
        val d = VanPolicy.decide(input().copy(saveVan = null))
        assertEquals(VanSource.SAVE, d.source); assertNull(d.position)
    }

    @Test fun clockBackwardsAgeNotNegative() {
        assertEquals(0L, VanPolicy.decide(input(mem = mem, now = 2500)).ageMs)
    }
}
