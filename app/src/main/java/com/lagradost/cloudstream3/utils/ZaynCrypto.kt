package com.lagradost.cloudstream3.utils

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase B: javax.crypto replacement for the upstream cryptography lib
 * (Kotlin metadata 2.3.0 — unreadable by the frozen Kotlin 1.9.24
 * toolchain). Same algorithms the upstream helpers used: AES-CBC with
 * PKCS5 padding, AES-GCM with a 128-bit tag, MD5, SecureRandom.
 */
object ZaynCrypto {
    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray, padding: Boolean = true): ByteArray {
        val cipher = Cipher.getInstance(if (padding) "AES/CBC/PKCS5Padding" else "AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray, padding: Boolean = true): ByteArray {
        val cipher = Cipher.getInstance(if (padding) "AES/CBC/PKCS5Padding" else "AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }

    fun md5(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun md5(data: ByteArray, offset: Int, len: Int): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(data, offset, len)
        return md.digest()
    }

    fun randomBytes(length: Int): ByteArray = ByteArray(length).also { SecureRandom().nextBytes(it) }
}
