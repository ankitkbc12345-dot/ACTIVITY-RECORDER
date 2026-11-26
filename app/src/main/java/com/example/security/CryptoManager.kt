package com.example.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {
  private const val ANDROID_KEYSTORE = "AndroidKeyStore"
  private const val KEY_ALIAS = "pdr_local_aes_gcm_key_v1"
  private const val GCM_TAG_BITS = 128
  private const val GCM_IV_BYTES = 12
  private const val PBKDF2_ITERATIONS = 120_000
  private const val PBKDF2_KEY_BITS = 256
  private val secureRandom = SecureRandom()

  fun hashPassword(password: String): String {
    val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
    val hash = pbkdf2(password.toCharArray(), salt)
    return "${Base64.encodeToString(salt, Base64.NO_WRAP)}:${Base64.encodeToString(hash, Base64.NO_WRAP)}"
  }

  fun verifyPassword(password: String, storedVerifier: String): Boolean {
    val parts = storedVerifier.split(':')
    if (parts.size != 2) return false
    return try {
      val salt = Base64.decode(parts[0], Base64.NO_WRAP)
      val expected = Base64.decode(parts[1], Base64.NO_WRAP)
      val actual = pbkdf2(password.toCharArray(), salt)
      MessageDigest.isEqual(expected, actual)
    } catch (_: Exception) {
      false
    }
  }

  private fun pbkdf2(chars: CharArray, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    return factory.generateSecret(spec).encoded
  }

  fun generatePairingSecret(): String {
    val bytes = ByteArray(32).also { secureRandom.nextBytes(it) }
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
  }

  fun generateSixDigitCode(): String {
    val num = secureRandom.nextInt(1_000_000)
    return String.format("%06d", num)
  }

  fun sha256Hex(input: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input)
    return digest.joinToString("") { "%02x".format(it) }
  }

  fun sha256Hex(input: String): String = sha256Hex(input.toByteArray(Charsets.UTF_8))

  fun isKeystoreActive(): Boolean {
    return try {
      val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
      ks.load(null)
      true
    } catch (_: Exception) {
      false
    }
  }

  @Volatile private var cachedSecretKey: SecretKey? = null
  private val keyLock = Any()

  private fun getOrCreateKeystoreSecretKey(context: Context): SecretKey {
    cachedSecretKey?.let { return it }
    synchronized(keyLock) {
      cachedSecretKey?.let { return it }
      val resolved =
        try {
          val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
          val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
          if (existing != null) {
            existing
          } else {
            val keyGenerator =
              KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec =
              KeyGenParameterSpec.Builder(
                  KEY_ALIAS,
                  KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
          }
        } catch (_: Exception) {
          // Fallback for JVM test environments (e.g., Robolectric) where AndroidKeyStore provider is absent
          val prefs = context.getSharedPreferences("pdr_fallback_keystore", Context.MODE_PRIVATE)
          val encoded = prefs.getString("aes_key", null)
          val keyBytes =
            if (encoded != null) {
              Base64.decode(encoded, Base64.NO_WRAP)
            } else {
              ByteArray(32).also {
                secureRandom.nextBytes(it)
                prefs.edit().putString("aes_key", Base64.encodeToString(it, Base64.NO_WRAP)).apply()
              }
            }
          SecretKeySpec(keyBytes, "AES")
        }
      cachedSecretKey = resolved
      return resolved
    }
  }

  fun encryptLocalString(context: Context, plaintext: String): String {
    if (plaintext.isEmpty()) return ""
    val key = getOrCreateKeystoreSecretKey(context)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val iv = cipher.iv
    val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    return "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(cipherBytes, Base64.NO_WRAP)}"
  }

  fun decryptLocalString(context: Context, encoded: String): String {
    if (encoded.isEmpty()) return ""
    val parts = encoded.split(':')
    if (parts.size != 2) return ""
    return try {
      val iv = Base64.decode(parts[0], Base64.NO_WRAP)
      val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
      val key = getOrCreateKeystoreSecretKey(context)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
      String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    } catch (_: Exception) {
      ""
    }
  }

  fun encryptField(context: Context, plaintext: String): String {
    if (plaintext.isEmpty()) return ""
    return "enc:${encryptLocalString(context, plaintext)}"
  }

  fun decryptField(context: Context, stored: String): String {
    if (stored.isEmpty()) return ""
    return if (stored.startsWith("enc:")) {
      decryptLocalString(context, stored.removePrefix("enc:")).ifEmpty { "Unavailable" }
    } else {
      stored
    }
  }

  fun encryptWithPassphrase(plaintext: ByteArray, passphrase: String): ByteArray {
    val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
    val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
    val keyBytes = pbkdf2(passphrase.toCharArray(), salt)
    val key = SecretKeySpec(keyBytes, "AES")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
    val encrypted = cipher.doFinal(plaintext)
    return salt + iv + encrypted
  }

  fun decryptWithPassphrase(payload: ByteArray, passphrase: String): ByteArray {
    require(payload.size > 16 + GCM_IV_BYTES) { "Invalid encrypted payload size" }
    val salt = payload.copyOfRange(0, 16)
    val iv = payload.copyOfRange(16, 16 + GCM_IV_BYTES)
    val encrypted = payload.copyOfRange(16 + GCM_IV_BYTES, payload.size)
    val keyBytes = pbkdf2(passphrase.toCharArray(), salt)
    val key = SecretKeySpec(keyBytes, "AES")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
    return cipher.doFinal(encrypted)
  }

  fun encryptWithSharedSecret(plaintext: ByteArray, base64Secret: String): ByteArray {
    val rawKey = MessageDigest.getInstance("SHA-256").digest(base64Secret.toByteArray(Charsets.UTF_8))
    val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
    val key = SecretKeySpec(rawKey, "AES")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
    return iv + cipher.doFinal(plaintext)
  }

  fun decryptWithSharedSecret(payload: ByteArray, base64Secret: String): ByteArray {
    require(payload.size > GCM_IV_BYTES) { "Invalid encrypted transfer frame" }
    val rawKey = MessageDigest.getInstance("SHA-256").digest(base64Secret.toByteArray(Charsets.UTF_8))
    val iv = payload.copyOfRange(0, GCM_IV_BYTES)
    val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
    val key = SecretKeySpec(rawKey, "AES")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
    return cipher.doFinal(encrypted)
  }

  fun getLocalDeviceModel(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()
  }
}
