package com.test4.smsgateway.data.remote

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.test4.smsgateway.platform.telephony.AndroidSimManager
import com.test4.smsgateway.platform.telephony.SimAvailability
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Run from a dedicated background thread / foreground service, never the UI thread.
 * Polls one job and waits for Android's actual SMS sent broadcast before ACK.
 * On uncertain delivery, do NOT report failure and resend automatically.
 */
class MaqwatiGatewayRunner(
    private val context: Context,
    private val api: MaqwatiGatewayApi,
    private val simManager: AndroidSimManager = AndroidSimManager(context),
) {
    fun runOne(): Boolean {
        val response = JSONObject(api.poll())
        if (response.isNull("job")) return false
        val job = response.getJSONObject("job")
        val id = UUID.fromString(job.getString("id"))
        val recipient = job.getString("recipient")
        val message = job.getString("text")
        require(recipient.matches(Regex("^\\+967[0-9]{9}$")))
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            api.report(id, "failed", 0)
            return true
        }
        val available = simManager.getAvailableSims() as? SimAvailability.Available
        val sim = available?.subscriptions?.firstOrNull { it.isReady && it.isDefaultForSms }
            ?: available?.subscriptions?.firstOrNull { it.isReady }
        if (sim == null) {
            api.report(id, "failed", 0)
            return true
        }
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val latch = CountDownLatch(1)
        var success = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                success = resultCode == android.app.Activity.RESULT_OK
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val pending = PendingIntent.getBroadcast(
                context, (System.nanoTime() and 0x7fffffff).toInt(),
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val manager = if (Build.VERSION.SDK_INT >= 31)
                context.getSystemService(SmsManager::class.java)
                    .createForSubscriptionId(sim.subscriptionId)
            else SmsManager.getSmsManagerForSubscriptionId(sim.subscriptionId)
            val parts = manager.divideMessage(message)
            // Each segment needs its own confirmation; one sent result is not enough.
            // Limit pilot to a single SMS segment until multipart acknowledgements are implemented.
            require(parts.size == 1) { "Multipart SMS not yet supported safely" }
            manager.sendTextMessage(recipient, null, message, pending, null)
            if (latch.await(30, TimeUnit.SECONDS)) {
                api.report(id, if (success) "sent" else "failed", sim.slotIndex)
            }
            // Timeout is uncertain: allow server lease to expire, but do not ACK 'failed'.
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        }
        return true
    }
}
