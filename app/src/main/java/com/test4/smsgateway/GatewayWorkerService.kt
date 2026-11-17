package com.test4.smsgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import com.test4.smsgateway.data.remote.MaqwatiGatewayRunner
import com.test4.smsgateway.data.remote.MaqwatiGatewayApi
import com.test4.smsgateway.security.DeviceIdentity

/** User-started foreground worker. Never sends unless the server approves the device. */
class GatewayWorkerService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val prefs by lazy { getSharedPreferences("gateway_settings", MODE_PRIVATE) }
    private fun diagnostic(state: String) {
        prefs.edit().putString("worker_diagnostic", state).apply()
        Log.i("FIN25Worker", state)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val url = getSharedPreferences("gateway_settings", MODE_PRIVATE).getString("server_url", "") ?: ""
        if (!url.startsWith("https://")) { diagnostic("عنوان HTTPS غير صالح"); stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "SMS Gateway", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Maqwati SMS Gateway")
            .setContentText("Gateway connection active")
            .setOngoing(true).build()
        startForeground(1001, notification)
        running = true
        diagnostic("العامل يعمل؛ بانتظار فحص الاتصال")
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val identity = DeviceIdentity(this)
            val api = MaqwatiGatewayApi(url, identity.deviceId, DeviceIdentity.ALIAS)
            val runner = MaqwatiGatewayRunner(this, api)
                while (running && !Thread.currentThread().isInterrupted) {
                try {
                    api.heartbeat()
                    diagnostic("الجهاز معتمد ومتصل؛ جارٍ فحص إذن الإرسال المحلي")
                    // A local explicit opt-in AND server-side operator flag are required.
                    if (getSharedPreferences("gateway_settings", MODE_PRIVATE)
                            .getBoolean("sms_test_opt_in", false)) {
                        val processed = runner.runOne()
                        diagnostic(if (processed) "تمت معالجة مهمة؛ راجع سجل الخادم" else "الاتصال ناجح؛ لا توجد مهمة أو إذن إرسال")
                    } else {
                        diagnostic("الإرسال المحلي معطّل؛ فعّل الاختبار يدوياً بعد الاعتماد")
                    }
                } catch (e: Exception) {
                    val detail = when (e) {
                        is com.test4.smsgateway.data.remote.GatewayHttpException -> "HTTP ${e.statusCode}"
                        is javax.net.ssl.SSLException -> "فشل التحقق من شهادة HTTPS"
                        is java.net.UnknownHostException -> "تعذر الوصول إلى اسم الخادم"
                        is java.net.SocketTimeoutException -> "انتهت مهلة الاتصال"
                        else -> "خطأ اتصال أو تشغيل: ${e.javaClass.simpleName}"
                    }
                    diagnostic(detail)
                }
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
            }
            stopSelf()
        }.also { it.start() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        running = false
        thread?.interrupt()
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP = "com.test4.smsgateway.STOP"
        private const val CHANNEL = "maqwati_gateway_worker"
    }
}
