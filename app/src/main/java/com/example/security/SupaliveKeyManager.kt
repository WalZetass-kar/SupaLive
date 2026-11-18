package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface KeyProvider {
  fun getOrCreateSecretKey(version: Int): SecretKey
}

class AndroidKeyStoreProvider : KeyProvider {
  private val keyCache = ConcurrentHashMap<Int, SecretKey>()

  override fun getOrCreateSecretKey(version: Int): SecretKey {
    keyCache[version]?.let { return it }
    synchronized(this) {
      keyCache[version]?.let { return it }
      val alias = "${KEY_ALIAS_PREFIX}_v$version"
      val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
      val existing = keyStore.getKey(alias, null) as? SecretKey
      if (existing != null) {
        keyCache[version] = existing
        return existing
      }

      val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
      val spec =
        KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
          )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .setRandomizedEncryptionRequired(false)
          .build()
      generator.init(spec)
      return generator.generateKey().also { keyCache[version] = it }
    }
  }

  private companion object {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS_PREFIX = "supalive_publishable_master"
  }
}

/**
 * Encrypts and decrypts Supabase Publishable Keys at rest using AES-256-GCM
 * with a unique 12-byte random nonce/IV per encryption and versioned ciphertext:
 * `v<version>:<iv_base64>:<ciphertext_base64>`
 */
class SupaliveKeyManager(
  private val keyProvider: KeyProvider = SHARED_KEY_PROVIDER,
  private val currentVersion: Int = 1,
) {
  private val secureRandom = SecureRandom()

  fun encrypt(plaintext: String): Pair<String, Int> {
    val secretKey = keyProvider.getOrCreateSecretKey(currentVersion)
    val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
    val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

    val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
    val cipherB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
    val payload = "v$currentVersion:$ivB64:$cipherB64"
    return payload to currentVersion
  }

  fun decrypt(encodedPayload: String): Result<String> {
    return try {
      val parts = encodedPayload.split(':')
      require(parts.size == 3 && parts[0].startsWith("v")) { "Unsupported ciphertext format" }
      val version = parts[0].removePrefix("v").toInt()
      val iv = Base64.decode(parts[1], Base64.NO_WRAP)
      val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)

      val secretKey = keyProvider.getOrCreateSecretKey(version)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
      val plainBytes = cipher.doFinal(ciphertext)
      Result.success(String(plainBytes, StandardCharsets.UTF_8))
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  private companion object {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_IV_LENGTH_BYTES = 12
    const val GCM_TAG_LENGTH_BITS = 128
    val SHARED_KEY_PROVIDER: KeyProvider by lazy { AndroidKeyStoreProvider() }
  }
}
