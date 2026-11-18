package com.test4.smsgateway.data.remote

import com.test4.smsgateway.security.DeviceIdentity
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.KeyStore
import java.security.Signature
import android.util.Base64

/** FIN25 challenge proof. Does not activate the device or send SMS. */
class GatewayEnrollmentClient(private val baseUrl: String, private val identity: DeviceIdentity) {
    init { require(baseUrl.startsWith("https://")) { "HTTPS required" } }
    private fun post(path: String, body: JSONObject): JSONObject {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "{}"
            if (status !in 200..299) throw IllegalStateException("HTTP $status: $text")
            return JSONObject(text)
        } finally { conn.disconnect() }
    }
    fun requestEnrollment(): JSONObject {
        identity.ensureKey()
        return post("/v1/gateway/enrollment/request", JSONObject()
            .put("deviceId", identity.deviceId)
            .put("publicKeyPem", identity.publicKeyPem()))
    }
    fun prove(): JSONObject {
        identity.ensureKey()
        val id = identity.deviceId
        val challenge = post("/v1/gateway/challenge", JSONObject().put("deviceId", id))
            .getString("challenge")
        val payload = "MAQWATI-GATEWAY-ENROLL-V1\n$id\n$challenge"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = requireNotNull(store.getKey(DeviceIdentity.ALIAS, null)) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(key)
            update(payload.toByteArray(Charsets.UTF_8))
        }.sign()
        return post("/v1/gateway/prove", JSONObject()
            .put("deviceId", id).put("challenge", challenge)
            .put("signature", Base64.encodeToString(signature, Base64.NO_WRAP)))
    }
}
