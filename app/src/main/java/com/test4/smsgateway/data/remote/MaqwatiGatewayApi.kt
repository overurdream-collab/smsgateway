package com.test4.smsgateway.data.remote

import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.Signature
import java.util.Base64
import java.util.UUID

/**
 * Maqwati OTP gateway API transport.
 * Run on Dispatchers.IO, never on Android main thread.
 * Server must be HTTPS with a valid trusted certificate.
 * Device RSA key must be enrolled and approved on the server before polling.
 */
class MaqwatiGatewayApi(
    private val baseUrl: String,
    private val deviceId: String,
    private val androidKeyStoreAlias: String,
) {
    init { require(baseUrl.startsWith("https://")) { "HTTPS required" } }

    private fun signedPost(path: String, body: String): String {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val canonical = listOf("POST", path, timestamp, nonce, body).joinToString("\n")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = requireNotNull(store.getKey(androidKeyStoreAlias, null)) {
            "Device key is not enrolled"
        } as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(canonical.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Device-Id", deviceId)
            conn.setRequestProperty("X-Timestamp", timestamp)
            conn.setRequestProperty("X-Nonce", nonce)
            conn.setRequestProperty("X-Signature", signature)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val response = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = response.bufferedReader().use { it.readText() }
            if (conn.responseCode !in 200..299) throw GatewayHttpException(conn.responseCode)
            return text
        } finally { conn.disconnect() }
    }

    fun poll(): String = signedPost("/v1/gateway/poll", "{}")
    fun heartbeat(): String = signedPost("/v1/gateway/heartbeat", "{}")
    fun report(jobId: UUID, status: String, simSlot: Int): String {
        require(status in setOf("sent", "failed", "delivered"))
        require(simSlot >= 0)
        return signedPost("/v1/gateway/result",
            """{"jobId":"$jobId","status":"$status","simSlot":$simSlot}""")
    }
}

class GatewayHttpException(val statusCode: Int) : RuntimeException("Gateway HTTP $statusCode")
