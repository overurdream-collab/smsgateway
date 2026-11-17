package com.test4.smsgateway.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.RSAKeyGenParameterSpec
import java.util.UUID

/** The private key never leaves AndroidKeyStore. Registration is admin-approved. */
class DeviceIdentity(private val context: Context) {
    companion object { const val ALIAS = "maqwati_gateway_signing_v1" }
    private val preferences = context.getSharedPreferences("gateway_identity", Context.MODE_PRIVATE)
    val deviceId: String
        get() = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("device_id", it).apply()
        }

    fun ensureKey(): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).setAlgorithmParameterSpec(RSAKeyGenParameterSpec(3072, RSAKeyGenParameterSpec.F4))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setUserAuthenticationRequired(false)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
        return publicKeyPem()
    }

    fun publicKeyPem(): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = requireNotNull(store.getCertificate(ALIAS)) { "Generate the device key first" }.publicKey
        val b64 = Base64.encodeToString(key.encoded, Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n" + b64.chunked(64).joinToString("\n") +
            "\n-----END PUBLIC KEY-----"
    }

    fun fingerprint(): String {
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getCertificate(ALIAS).publicKey.encoded
        return MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString(":") { "%02X".format(it) }
    }
}
