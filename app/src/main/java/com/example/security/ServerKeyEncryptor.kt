package com.example.security

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Encrypts Supabase Publishable Keys with the server's RSA-2048 public key
 * (RSA-OAEP with SHA-256) so scheduled GitHub Actions workers can decrypt and
 * run health checks without ever embedding a decryption key in the APK.
 */
object ServerKeyEncryptor {
  private const val SERVER_KEY_VERSION = 1
  private const val TRANSFORMATION = "RSA/ECB/OAEPPadding"
  private const val PUBLIC_KEY_BASE64 =
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvCeNDBbPijs03RF5UICF" +
      "i8kSqa739ROKB5MXNWXgeGNRJnZCYSkcs+YRW8YQzHvdMP0EDJyGZPYSRJ60Vqe7" +
      "6euwkBDt3JVG2IMruRRhyMpj6a4O/8fJerpzGyaI4vsnL3zqsasAtPPQ2pTqWw6Y" +
      "zbvhTe3wzsiwZW833hp+8K0t1YMb1ecIXRBXeN+GMsHA97Tbi25p/DUwpm+8RGlg" +
      "+pcDVFOtefnFKGX7KySbFEHHvQWs3wUsawI3aOMZPhbGWDm9Ke5S4XrN6EqW6a8f" +
      "FuRwsJ/ce27EJDixUjrsHT9MOMNySIwp0vWaPidjl5rEjtu+whVrIPHkYlsngbdK" +
      "2wIDAQAB"

  private val publicKey: PublicKey by lazy {
    val keyBytes = Base64.decode(PUBLIC_KEY_BASE64, Base64.DEFAULT)
    KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
  }

  fun encrypt(plaintext: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    val oaepSpec =
      OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA256,
        PSource.PSpecified.DEFAULT,
      )
    cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepSpec)
    val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
    val cipherB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
    return "v$SERVER_KEY_VERSION:$cipherB64"
  }
}
