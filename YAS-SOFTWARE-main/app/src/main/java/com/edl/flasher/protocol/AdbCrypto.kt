package com.edl.flasher.protocol

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

/**
 * RSA key-pair generation/storage and the ADB AUTH signing scheme used by the
 * real `adb` host tool. This re-implements the public, documented wire format
 * only -- it does not and cannot skip the on-device "Allow USB debugging?"
 * authorization prompt the first time a given key talks to a given phone.
 */
object AdbCrypto {

    private const val KEY_LEN_BITS = 2048
    private const val KEY_LEN_BYTES = KEY_LEN_BITS / 8
    private const val KEY_LEN_WORDS = KEY_LEN_BYTES / 4

    fun loadOrGenerateKeyPair(context: Context): KeyPair {
        val dir = File(context.filesDir, "adbkey")
        val privFile = File(dir, "adbkey.priv")
        val pubFile = File(dir, "adbkey.pub")
        if (privFile.exists() && pubFile.exists()) {
            runCatching {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privFile.readText(), Base64.DEFAULT)))
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubFile.readText(), Base64.DEFAULT)))
                return KeyPair(pub, priv)
            }
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(KEY_LEN_BITS)
        val pair = kpg.generateKeyPair()
        dir.mkdirs()
        runCatching {
            privFile.writeText(Base64.encodeToString(pair.private.encoded, Base64.DEFAULT))
            pubFile.writeText(Base64.encodeToString(pair.public.encoded, Base64.DEFAULT))
        }
        return pair
    }

    /** Signs a 20-byte ADB auth token using the ADB-specific PKCS#1 v1.5 padding
     *  (0x00 0x01 0xFF...0xFF 0x00 + SHA-1 DigestInfo prefix + 20-byte token). */
    fun sign(keyPair: KeyPair, token: ByteArray): ByteArray {
        val padded = ByteArray(KEY_LEN_BYTES)
        padded[0] = 0x00
        padded[1] = 0x01
        // ADB signs the token as a pre-hashed SHA-1 digest: PKCS#1 v1.5 padding WITH the SHA-1 DigestInfo prefix.
        val digestInfoPrefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val padLen = KEY_LEN_BYTES - 3 - digestInfoPrefix.size - token.size
        for (i in 0 until padLen) padded[2 + i] = 0xFF.toByte()
        padded[2 + padLen] = 0x00
        System.arraycopy(digestInfoPrefix, 0, padded, 3 + padLen, digestInfoPrefix.size)
        System.arraycopy(token, 0, padded, 3 + padLen + digestInfoPrefix.size, token.size)

        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(padded)
    }

    /** Encodes the public key in ADB's own binary RSAPublicKey struct format,
     *  base64s it and appends a " comment" field, matching what a real adb
     *  client sends for AUTH_RSAPUBLICKEY. */
    fun publicKeyToAdbFormat(keyPair: KeyPair): String {
        val priv = keyPair.private as RSAPrivateCrtKey
        val n = priv.modulus
        val e = priv.publicExponent

        val r32 = BigInteger.ONE.shiftLeft(32)
        val r = BigInteger.ONE.shiftLeft(32 * KEY_LEN_WORDS)
        val rr = r.multiply(r).mod(n)
        val n0 = n.mod(r32)
        val n0invRaw = n0.modInverse(r32)
        val n0inv = r32.subtract(n0invRaw).mod(r32)

        val buf = java.io.ByteArrayOutputStream()
        buf.write(leWord(KEY_LEN_WORDS))
        buf.write(leWord(n0inv.toInt()))
        buf.write(bigIntToLeWords(n, KEY_LEN_WORDS))
        buf.write(bigIntToLeWords(rr, KEY_LEN_WORDS))
        buf.write(leWord(e.toInt()))

        val encoded = Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
        return "$encoded yas-flash-edl@mtp-adb"
    }

    private fun leWord(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun bigIntToLeWords(value: BigInteger, words: Int): ByteArray {
        var v = value
        val out = ByteArray(words * 4)
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        for (i in 0 until words) {
            val word = v.and(mask).toInt()
            val wordBytes = leWord(word)
            System.arraycopy(wordBytes, 0, out, i * 4, 4)
            v = v.shiftRight(32)
        }
        return out
    }
}
