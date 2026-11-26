package com.lagradost.cloudstream3.extractors.helper

import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import kotlin.math.min
import java.security.MessageDigest
import com.lagradost.cloudstream3.utils.ZaynCrypto

/**
 * Conforming with CryptoJS AES method
 */
// see https://gist.github.com/thackerronak/554c985c3001b16810af5fc0eb5c358f
@Suppress("unused", "FunctionName", "SameParameterValue")
object CryptoJS {
    private const val KEY_SIZE = 256
    private const val IV_SIZE = 128

    // Seriously crypto-js, what's wrong with you?
    private const val APPEND = "Salted__"

    /**
     * Encrypt
     * @param password passphrase
     * @param plainText plain string
     */
    fun encrypt(password: String, plainText: String): String {
        val saltBytes = generateSalt(8)
        val key = ByteArray(KEY_SIZE / 8)
        val iv = ByteArray(IV_SIZE / 8)
        evpkdf(password.encodeToByteArray(), KEY_SIZE, IV_SIZE, saltBytes, 1, key, iv)

        val cipherText = ZaynCrypto.aesCbcEncrypt(key, iv, plainText.encodeToByteArray())

        // Create CryptoJS-like encrypted: "Salted__" || salt || ciphertext
        val sBytes = APPEND.encodeToByteArray()
        val b = ByteArray(sBytes.size + saltBytes.size + cipherText.size)
        sBytes.copyInto(destination = b, destinationOffset = 0)
        saltBytes.copyInto(destination = b, destinationOffset = sBytes.size)
        cipherText.copyInto(destination = b, destinationOffset = sBytes.size + saltBytes.size)

        return base64Encode(b)
    }

    /**
     * Decrypt
     * Thanks Artjom B. for this: http://stackoverflow.com/a/29152379/4405051
     * @param password passphrase
     * @param cipherText encrypted string
     */
    fun decrypt(password: String, cipherText: String): String {
        val ctBytes = base64DecodeArray(cipherText)
        val saltBytes = ctBytes.copyOfRange(8, 16)
        val cipherTextBytes = ctBytes.copyOfRange(16, ctBytes.size)

        val key = ByteArray(KEY_SIZE / 8)
        val iv = ByteArray(IV_SIZE / 8)
        evpkdf(password.encodeToByteArray(), KEY_SIZE, IV_SIZE, saltBytes, 1, key, iv)

        val plainText = ZaynCrypto.aesCbcDecrypt(key, iv, cipherTextBytes)
        return plainText.decodeToString()
    }

    private fun evpkdf(
        password: ByteArray,
        keySize: Int,
        ivSize: Int,
        salt: ByteArray,
        iterations: Int,
        resultKey: ByteArray,
        resultIv: ByteArray,
    ): ByteArray {
        val keySize = keySize / 32
        val ivSize = ivSize / 32
        val targetKeySize = keySize + ivSize
        val derivedBytes = ByteArray(targetKeySize * 4)
        var numberOfDerivedWords = 0
        var block: ByteArray? = null

        while (numberOfDerivedWords < targetKeySize) {
            val md = MessageDigest.getInstance("MD5")
            if (block != null) md.update(block)
            md.update(password)
            md.update(salt)
            block = md.digest()

            for (i in 1 until iterations) {
                val iterMd = MessageDigest.getInstance("MD5")
                iterMd.update(block!!)
                block = iterMd.digest()
            }

            block!!.copyInto(
                destination = derivedBytes,
                destinationOffset = numberOfDerivedWords * 4,
                startIndex = 0,
                endIndex = min(block!!.size, (targetKeySize - numberOfDerivedWords) * 4)
            )

            numberOfDerivedWords += block!!.size / 4
        }

        derivedBytes.copyInto(destination = resultKey, destinationOffset = 0, startIndex = 0, endIndex = keySize * 4)
        derivedBytes.copyInto(destination = resultIv, destinationOffset = 0, startIndex = keySize * 4, endIndex = (keySize + ivSize) * 4)
        return derivedBytes // key + iv
    }


    private fun generateSalt(length: Int): ByteArray =
        ZaynCrypto.randomBytes(length)
}
