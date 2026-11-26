package com.lagradost.cloudstream3.extractors.helper

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.utils.AppUtils
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import com.lagradost.cloudstream3.utils.ZaynCrypto

object AesHelper {

    suspend fun cryptoAESHandler(
        data: String,
        pass: ByteArray,
        encrypt: Boolean = true,
        padding: Boolean = true,
    ): String? {
        val parse = AppUtils.tryParseJson<AesData>(data) ?: return null
        val (key, iv) = generateKeyAndIv(
            password = pass,
            salt = parse.s.hexToByteArray(),
            ivLength = parse.iv.length / 2,
            saltLength = parse.s.length / 2,
        ) ?: return null

        return if (!encrypt) {
            ZaynCrypto.aesCbcDecrypt(key, iv, base64DecodeArray(parse.ct), padding).decodeToString()
        } else {
            base64Encode(ZaynCrypto.aesCbcEncrypt(key, iv, parse.ct.encodeToByteArray(), padding))
        }
    }

    @Deprecated(
        message = "Set padding = false for no padding",
        level = DeprecationLevel.WARNING,
    )
    fun cryptoAESHandler(
        data: String,
        pass: ByteArray,
        encrypt: Boolean = true,
        padding: String,
    ): String? {
        // If it ends with NoPadding (e.g. "AES/CBC/NoPadding"), then it
        // doesn't have padding, otherwise we treat as if it does.
        val hasPadding = !padding.endsWith("NoPadding")
        return runBlocking { cryptoAESHandler(data, pass, encrypt, hasPadding) }
    }

    // https://stackoverflow.com/a/41434590/8166854
    // EVP_BytesToKey-style derivation (MD5, 1 iteration default) as in upstream.
    fun generateKeyAndIv(
password: <redacted>
        salt: ByteArray,
        keyLength: Int = 32,
        ivLength: Int,
        saltLength: Int,
        iterations: Int = 1,
    ): Pair<ByteArray, ByteArray>? {
        return try {
            val digestLength = 16 // MD5 digest is always 16 bytes
            val targetKeySize = keyLength + ivLength
            val requiredLength = (targetKeySize + digestLength - 1) / digestLength * digestLength
            val generatedData = ByteArray(requiredLength)
            var generatedLength = 0

            while (generatedLength < targetKeySize) {
                val md = MessageDigest.getInstance("MD5")
                if (generatedLength > 0) {
                    md.update(generatedData, generatedLength - digestLength, digestLength)
                }
                md.update(password)
                md.update(salt, 0, saltLength)
                var digest = md.digest()

                for (i in 1 until iterations) {
                    val iterMd = MessageDigest.getInstance("MD5")
                    iterMd.update(digest)
                    digest = iterMd.digest()
                }
                digest.copyInto(generatedData, generatedLength)

                generatedLength += digestLength
            }

            generatedData.copyOfRange(0, keyLength) to
                generatedData.copyOfRange(keyLength, targetKeySize)
        } catch (_: Exception) {
            null
        }
    }


    fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    @Serializable
    private data class AesData(
        @JsonProperty("ct") @SerialName("ct") val ct: String,
        @JsonProperty("iv") @SerialName("iv") val iv: String,
        @JsonProperty("s") @SerialName("s") val s: String,
    )
}
