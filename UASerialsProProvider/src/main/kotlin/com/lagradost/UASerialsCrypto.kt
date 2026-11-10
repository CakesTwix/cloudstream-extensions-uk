package com.lagradost

import com.google.gson.annotations.SerializedName
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class AesData(
    @SerializedName("ciphertext") val ct: String? = null,
    @SerializedName("salt") val s: String? = null,
    @SerializedName("iv") val iv: String? = null,
    @SerializedName("iterations") val iterations: Int = 999,
)

private fun String.decodeHex(): ByteArray {
    val clean = filter { it in "0123456789abcdefABCDEF" }
    check(clean.length % 2 == 0) { "Must have an even length" }
    return clean.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

// THANKS to Hexated!
internal fun cryptojsAESHandler(
    data: AesData?,
    pass: String,
    encrypt: Boolean = true
): String {
    if (data == null || data.s.isNullOrBlank() || data.iv.isNullOrBlank() || data.ct.isNullOrBlank()) {
        return ""
    }
    val salt = try {
        data.s.decodeHex()
    } catch (e: Exception) {
        return ""
    }
    val iv = try {
        data.iv.decodeHex()
    } catch (e: Exception) {
        return ""
    }
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
    val spec = PBEKeySpec(pass.toCharArray(), salt, 999, 256)
    val key = factory.generateSecret(spec)
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    return try {
        if (!encrypt) {
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.encoded, "AES"),
                IvParameterSpec(iv)
            )
            String(cipher.doFinal(Base64.getDecoder().decode(data.ct)))
        } else {
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key.encoded, "AES"),
                IvParameterSpec(iv)
            )
            Base64.getEncoder().encodeToString(cipher.doFinal(data.ct.toByteArray()))
        }
    } catch (e: Exception) {
        ""
    }
}
