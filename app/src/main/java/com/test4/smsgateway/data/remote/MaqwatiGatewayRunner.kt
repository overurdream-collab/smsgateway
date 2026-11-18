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
import com.test4.smsgateway.domain.model.*
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
    private val isActive: () -> Boolean = { true },
) : GatewayDispatchPort {
    fun runOne(): DispatchResult = GatewayDispatchCycle(this).runOne()

    override fun readiness(): DispatchReadiness {
        if (!isActive() || Thread.currentThread().isInterrupted ||
            !context.getSharedPreferences("gateway_settings", Context.MODE_PRIVATE)
                .getBoolean("sms_test_opt_in", false)) return DispatchReadiness.LocalDisabled
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) return DispatchReadiness.SmsPermissionRequired
        val available = simManager.getAvailableSims()
        if (available is SimAvailability.PermissionRequired) return DispatchReadiness.PhonePermissionRequired
        val sims = (available as? SimAvailability.Available)?.subscriptions
        val sim = sims?.firstOrNull { it.isReady && it.isDefaultForSms }
            ?: sims?.firstOrNull { it.isReady }
            ?: return DispatchReadiness.NoReadySim
        return DispatchReadiness.Ready(GatewaySim(sim.subscriptionId, sim.slotIndex))
    }

    override fun poll(): GatewayJob? {
        val response = JSONObject(api.poll())
        if (response.isNull("job")) return null
        val job = response.getJSONObject("job")
        val id = UUID.fromString(job.getString("id")).toString()
        val recipient = job.getString("recipient")
        require(recipient.matches(Regex("^\\+967[0-9]{9}$")))
        return GatewayJob(id, recipient, job.getString("text"))
    }

    override fun report(job: GatewayJob, status: String, sim: GatewaySim) {
        api.report(UUID.fromString(job.id), status, sim.slotIndex)
    }

    override fun submit(job: GatewayJob, sim: GatewaySim): SmsSubmission {
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
            val parts = manager.divideMessage(job.text)
            // Each segment needs its own confirmation; one sent result is not enough.
            // Limit pilot to a single SMS segment until multipart acknowledgements are implemented.
            if (parts.size != 1) return SmsSubmission.UNSUPPORTED_MESSAGE
            manager.sendTextMessage(job.recipient, null, job.text, pending, null)
            return if (latch.await(30, TimeUnit.SECONDS)) {
                if (success) SmsSubmission.SENT else SmsSubmission.FAILED
            } else SmsSubmission.UNKNOWN
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        }
    }
}
