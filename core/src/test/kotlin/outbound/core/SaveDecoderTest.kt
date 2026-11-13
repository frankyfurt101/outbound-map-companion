package outbound.core

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SaveDecoderTest {
    private val json = """{"CurrentLevelScene":"canyons","Player":{}}"""

    private fun expectFail(block: () -> Unit) {
        try {
            block()
            fail("expected SaveDecodeException")
        } catch (e: SaveDecodeException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test fun roundTripWithRandomKey() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        assertEquals(json, SaveDecoder.decode(TestSupport.encrypt(json, key), key))
    }

    @Test fun roundTripToleratesTrailingNewline() {
        val key = ByteArray(32) { it.toByte() }
        val enc = TestSupport.encrypt(json, key) + "\r\n".toByteArray()
        assertEquals(json, SaveDecoder.decode(enc, key))
    }

    @Test fun wrongKeyFails() {
        val key = ByteArray(32) { it.toByte() }
        val wrong = ByteArray(32) { (it + 1).toByte() }
        expectFail { SaveDecoder.decode(TestSupport.encrypt(json, key), wrong) }
    }

    @Test fun badKeyLengthFails() {
        expectFail { SaveDecoder.decode(TestSupport.encrypt(json, ByteArray(32)), ByteArray(16)) }
    }

    @Test fun corruptedInputFails() {
        val key = ByteArray(32) { it.toByte() }
        val enc = TestSupport.encrypt(json, key)
        expectFail { SaveDecoder.decode("not base64 !!!".toByteArray(), key) }
        expectFail { SaveDecoder.decode(ByteArray(0), key) }
        expectFail { SaveDecoder.decode(enc.copyOf(enc.size - 4), key) } // truncated: bad length
        expectFail { SaveDecoder.decode(enc.copyOf(enc.size - 8), key) } // truncated by a whole block group
        // tamper with the last cipher block -> padding failure
        val t = enc.copyOf(); t[t.size - 6] = if (t[t.size - 6] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte()
        expectFail { SaveDecoder.decode(t, key) }
    }

    @Test fun nonJsonPlaintextFails() {
        val key = ByteArray(32) { it.toByte() }
        expectFail { SaveDecoder.decode(TestSupport.encrypt("hello world", key), key) }
    }
}
