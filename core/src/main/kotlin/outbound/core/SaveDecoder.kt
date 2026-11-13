package outbound.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Typed failure for anything that goes wrong while turning save bytes into JSON text. */
class SaveDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * File bytes -> Base64 -> AES-256-CBC (IV = 16 zero bytes, PKCS7) -> UTF-8 JSON text.
 * Messages never contain key material or decrypted content.
 */
object SaveDecoder {
    const val KEY_SIZE = 32
    private const val BLOCK = 16

    fun decode(fileBytes: ByteArray, key: ByteArray): String {
        if (key.size != KEY_SIZE) throw SaveDecodeException("Key must be exactly $KEY_SIZE bytes")
        val cipherBytes = try {
            // Tolerate surrounding whitespace/newlines, but stay strict about the alphabet.
            val compact = String(fileBytes, Charsets.ISO_8859_1).filterNot { it.isWhitespace() }
            Base64.getDecoder().decode(compact)
        } catch (e: IllegalArgumentException) {
            throw SaveDecodeException("Save is not valid Base64", e)
        }
        if (cipherBytes.isEmpty() || cipherBytes.size % BLOCK != 0) {
            throw SaveDecodeException("Ciphertext length is not a positive multiple of $BLOCK")
        }
        val plain = try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding") // PKCS5 == PKCS7 for 16-byte blocks
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(BLOCK)))
            cipher.doFinal(cipherBytes)
        } catch (e: java.security.GeneralSecurityException) {
            throw SaveDecodeException("Decryption failed (wrong key or corrupted/partial file)", e)
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plain))
                .toString()
        } catch (e: CharacterCodingException) {
            throw SaveDecodeException("Decrypted bytes are not valid UTF-8", e)
        }
        if (!text.trimStart('﻿', ' ', '\n', '\r', '\t').startsWith("{")) {
            throw SaveDecodeException("Decrypted text is not a JSON object")
        }
        return text
    }
}
