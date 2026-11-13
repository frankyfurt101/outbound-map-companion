package outbound.core

import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.fail
import org.junit.Assume

object TestSupport {
    fun encrypt(json: String, key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        return Base64.getEncoder().encode(c.doFinal(json.toByteArray(Charsets.UTF_8)))
    }

    /** Value of env var [name]; FAILS when OUTBOUND_REQUIRE_SAMPLES=1 and missing, otherwise skips. */
    fun requireEnv(name: String): String {
        val v = System.getenv(name)
        if (v.isNullOrEmpty()) {
            if (System.getenv("OUTBOUND_REQUIRE_SAMPLES") == "1") fail("$name is not set (OUTBOUND_REQUIRE_SAMPLES=1)")
            Assume.assumeTrue("$name not set; skipping", false)
        }
        return v!!
    }

    fun requireFile(name: String): File {
        val f = File(requireEnv(name))
        if (!f.isFile) {
            if (System.getenv("OUTBOUND_REQUIRE_SAMPLES") == "1") fail("$name does not point to a readable file")
            Assume.assumeTrue("$name not a file; skipping", false)
        }
        return f
    }
}
