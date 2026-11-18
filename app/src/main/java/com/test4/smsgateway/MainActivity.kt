package com.test4.smsgateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.test4.smsgateway.security.DeviceIdentity
import com.test4.smsgateway.data.remote.MaqwatiGatewayApi
import com.test4.smsgateway.data.remote.GatewayEnrollmentClient
import com.test4.smsgateway.platform.telephony.AndroidSimManager
import com.test4.smsgateway.platform.telephony.SimAvailability

private val GatewayGreen = Color(0xFF16865B)
private val GatewayBackground = Color(0xFFF5F7F6)

class MainActivity : ComponentActivity() {
    private lateinit var simManager: AndroidSimManager
    private lateinit var identity: DeviceIdentity
    private var serverUrl by mutableStateOf("")
    private var smsTestOptIn by mutableStateOf(false)
    private var workerRequested by mutableStateOf(false)
    private var setupAttempted = false
    private var enrollmentStatus by mutableStateOf("لم يتم إنشاء مفتاح")
    private var workerDiagnostic by mutableStateOf("لم يبدأ فحص العامل بعد")
    private var keyFingerprint by mutableStateOf("")
    private var simAvailability by mutableStateOf<SimAvailability>(
        SimAvailability.PermissionRequired
    )

    private val smsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enrollmentStatus = if (granted) "تم منح إذن SMS؛ اضغط تصريح الاختبار لتفعيله" else "إذن SMS مطلوب"
    }

    private val phonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            simAvailability = if (granted) {
                simManager.getAvailableSims()
            } else {
                SimAvailability.PermissionRequired
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        simManager = AndroidSimManager(this)
        identity = DeviceIdentity(this)
        // Generate or reuse the Android Keystore identity automatically; never export private keys.
        try {
            identity.ensureKey()
            keyFingerprint = identity.fingerprint()
            enrollmentStatus = "هوية الجهاز جاهزة؛ التفعيل يتطلب اعتماد المسؤول"
        } catch (e: Exception) {
            enrollmentStatus = "تعذر إعداد هوية الجهاز: ${e.javaClass.simpleName}"
        }
        serverUrl = getSharedPreferences("gateway_settings", MODE_PRIVATE).getString("server_url", "") ?: ""
        // Never restore SMS opt-in across application restarts.
        getSharedPreferences("gateway_settings", MODE_PRIVATE).edit().putBoolean("sms_test_opt_in", false).apply()
        refreshSimStatus()
        refreshWorkerDiagnostic()
        // For previously enrolled devices, complete possession proof automatically.
        // New-device enrollment still requires an authorized server-side registration flow.
        if (!setupAttempted && serverUrl.trim().startsWith("https://")) {
            setupAttempted = true
            Thread {
                val result = try {
                    val api = MaqwatiGatewayApi(serverUrl.trim().trimEnd('/'), identity.deviceId, DeviceIdentity.ALIAS)
                    api.heartbeat()
                    "الجهاز معتمد ومتصل"
                } catch (e: com.test4.smsgateway.data.remote.GatewayHttpException) {
                    if (e.statusCode == 401 || e.statusCode == 409) {
                        try {
                            val proof = GatewayEnrollmentClient(serverUrl.trim().trimEnd('/'), identity).prove()
                            if (proof.optBoolean("proved")) "تم إثبات ملكية الجهاز؛ بانتظار موافقة المسؤول"
                            else "الجهاز غير معتمد؛ يلزم استكمال التسجيل لدى المسؤول"
                        } catch (_: Exception) {
                            try {
                                val enrollment = GatewayEnrollmentClient(serverUrl.trim().trimEnd('/'), identity)
                                val request = enrollment.requestEnrollment()
                                when (request.optString("status")) {
                                    "active" -> "الجهاز معتمد؛ أعد فحص الاتصال"
                                    "proved" -> "تم إثبات الجهاز؛ بانتظار اعتماد المسؤول"
                                    "pending" -> {
                                        try {
                                            enrollment.prove()
                                            "أُرسل التسجيل وأُثبت المفتاح؛ بانتظار اعتماد المسؤول"
                                        } catch (_: Exception) {
                                            "تم إرسال طلب التسجيل؛ بانتظار إكمال الإثبات والاعتماد"
                                        }
                                    }
                                    else -> "تعذر تحديد حالة تسجيل الجهاز"
                                }
                            } catch (e: Exception) {
                                "تعذر إرسال طلب التسجيل: ${e.javaClass.simpleName}"
                            }
                        }
                    } else "فشل الاتصال: HTTP ${e.statusCode}"
                } catch (e: Exception) { "تعذر فحص الجهاز: ${e.javaClass.simpleName}" }
                runOnUiThread { enrollmentStatus = result }
            }.start()
        }

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = GatewayGreen,
                    background = GatewayBackground,
                    surface = Color.White
                )
            ) {
                GatewayDashboard(
                    simAvailability = simAvailability,
                    serverUrl = serverUrl,
                    enrollmentStatus = enrollmentStatus,
                    keyFingerprint = keyFingerprint,
                    workerDiagnostic = workerDiagnostic,
                    onRefreshWorkerDiagnostic = { refreshWorkerDiagnostic() },
                    onServerUrlChange = { serverUrl = it },
                    onCreateIdentity = {
                        try {
                            identity.ensureKey()
                            keyFingerprint = identity.fingerprint()
                            enrollmentStatus = "معرّف الجهاز: ${identity.deviceId} — المفتاح جاهز، ينتظر موافقة المسؤول"
                        } catch (e: Exception) { enrollmentStatus = "فشل إنشاء المفتاح: ${e.javaClass.simpleName}" }
                    },
                    onCopyEnrollment = {
                        try {
                            val payload = "Device ID: ${identity.deviceId}\\nPublic key PEM:\\n${identity.publicKeyPem()}\\nSHA-256: ${identity.fingerprint()}"
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Gateway enrollment", payload))
                            enrollmentStatus = "تم نسخ بيانات التسجيل العامة للمسؤول؛ لا تشاركها إلا عبر قناة موثوقة"
                        } catch (e: Exception) { enrollmentStatus = "أنشئ المفتاح أولاً" }
                    },
                    onProveDevice = {
                        val url = serverUrl.trim().trimEnd('/')
                        if (!url.startsWith("https://")) enrollmentStatus = "عنوان HTTPS مطلوب"
                        else {
                            enrollmentStatus = "جارٍ إثبات المفتاح..."
                            Thread {
                                val result = try {
                                    val proof = GatewayEnrollmentClient(url, identity).prove()
                                    if (proof.optBoolean("proved")) "تم إثبات المفتاح؛ بانتظار اعتماد المسؤول" else "فشل الإثبات"
                                } catch (e: Exception) { "فشل الإثبات: ${e.javaClass.simpleName}" }
                                runOnUiThread { enrollmentStatus = result }
                            }.start()
                        }
                    },
                    smsTestOptIn = smsTestOptIn,
                    workerRequested = workerRequested,
                    onToggleSmsTest = {
                        val enabled = !smsTestOptIn
                        if (enabled && ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                            enrollmentStatus = "امنح إذن SMS ثم اضغط التفعيل اليدوي مرة أخرى"
                        } else {
                            smsTestOptIn = enabled
                            getSharedPreferences("gateway_settings", MODE_PRIVATE).edit().putBoolean("sms_test_opt_in", enabled).apply()
                            enrollmentStatus = if (enabled) "تصريح الإرسال المحلي مفعّل؛ يتطلب أيضاً اعتماد الجهاز وتمكين الإرسال بالخادم" else "تم تعطيل إرسال SMS"
                        }
                    },
                    onStartWorker = {
                        val url = serverUrl.trim().trimEnd('/')
                        if (!url.startsWith("https://")) enrollmentStatus = "عنوان HTTPS مطلوب"
                        else {
                            getSharedPreferences("gateway_settings", MODE_PRIVATE).edit().putString("server_url", url).apply()
                            try {
                                ContextCompat.startForegroundService(this, android.content.Intent(this, GatewayWorkerService::class.java))
                                workerRequested = true
                                enrollmentStatus = "تم طلب تشغيل البوابة؛ الإرسال يحتاج تصريح SMS وموافقة مستقلة"
                            } catch (e: Exception) { enrollmentStatus = "تعذر التشغيل: ${e.javaClass.simpleName}" }
                        }
                    },
                    onStopWorker = {
                        smsTestOptIn = false
                        getSharedPreferences("gateway_settings", MODE_PRIVATE).edit().putBoolean("sms_test_opt_in", false).apply()
                        stopService(android.content.Intent(this, GatewayWorkerService::class.java))
                        workerRequested = false
                        enrollmentStatus = "تم إيقاف العامل"
                    },
                    onTestConnection = {
                        val url = serverUrl.trim().trimEnd('/')
                        if (!url.startsWith("https://")) {
                            enrollmentStatus = "يجب استخدام عنوان HTTPS موثوق"
                        } else {
                            getSharedPreferences("gateway_settings", MODE_PRIVATE).edit().putString("server_url", url).apply()
                            enrollmentStatus = "جارٍ فحص الاتصال..."
                            Thread {
                                val result = try {
                                    MaqwatiGatewayApi(url, identity.deviceId, DeviceIdentity.ALIAS).heartbeat()
                                    "الخادم متصل والجهاز معتمد"
                                } catch (e: com.test4.smsgateway.data.remote.GatewayHttpException) {
                                    if (e.statusCode == 401 || e.statusCode == 409) {
                                        try {
                                            val enrollment = GatewayEnrollmentClient(url, identity)
                                            val response = enrollment.requestEnrollment()
                                            when (response.optString("status")) {
                                                "pending" -> {
                                                    try {
                                                        enrollment.prove()
                                                        "تم إثبات هوية الجهاز؛ بانتظار اعتماد المسؤول"
                                                    } catch (_: Exception) {
                                                        "طلب التسجيل معلق؛ تعذر إكمال إثبات المفتاح"
                                                    }
                                                }
                                                "proved" -> "هوية الجهاز مثبتة؛ بانتظار اعتماد المسؤول"
                                                "active" -> "الجهاز معتمد؛ تحقق من اتصال الخادم"
                                                "revoked" -> "الجهاز ملغى؛ تواصل مع المسؤول"
                                                else -> "حالة التسجيل غير معروفة"
                                            }
                                        } catch (enrollmentError: Exception) {
                                            "تعذر التسجيل الآمن: ${enrollmentError.javaClass.simpleName}"
                                        }
                                    } else "فشل الاتصال: HTTP ${e.statusCode}"
                                } catch (e: Exception) { "فشل فحص الاتصال: ${e.javaClass.simpleName}" }
                                runOnUiThread { enrollmentStatus = result }
                            }.start()
                        }
                    },
                    onScanSim = {
                        val granted = ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.READ_PHONE_STATE
                        ) == PackageManager.PERMISSION_GRANTED

                        if (granted) {
                            refreshSimStatus()
                        } else {
                            phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::simManager.isInitialized) refreshSimStatus()
        refreshWorkerDiagnostic()
    }

    private fun refreshWorkerDiagnostic() {
        workerRequested = GatewayWorkerService.isRunning
        smsTestOptIn = getSharedPreferences("gateway_settings", MODE_PRIVATE)
            .getBoolean("sms_test_opt_in", false)
        workerDiagnostic = getSharedPreferences("gateway_settings", MODE_PRIVATE)
            .getString("worker_diagnostic", "لم يبدأ فحص العامل بعد") ?: "غير معروف"
    }

    private fun refreshSimStatus() {
        simAvailability = simManager.getAvailableSims()
    }
}

@androidx.compose.runtime.Composable
private fun GatewayDashboard(
    simAvailability: SimAvailability,
    serverUrl: String,
    enrollmentStatus: String,
    keyFingerprint: String,
    workerDiagnostic: String,
    onRefreshWorkerDiagnostic: () -> Unit,
    onServerUrlChange: (String) -> Unit,
    onCreateIdentity: () -> Unit,
    onTestConnection: () -> Unit,
    onProveDevice: () -> Unit,
    smsTestOptIn: Boolean,
    workerRequested: Boolean,
    onToggleSmsTest: () -> Unit,
    onStartWorker: () -> Unit,
    onStopWorker: () -> Unit,
    onCopyEnrollment: () -> Unit,
    onScanSim: () -> Unit
) {
    // Refresh the diagnostic written by the foreground worker while the dashboard is visible.
    LaunchedEffect(Unit) {
        while (true) {
            onRefreshWorkerDiagnostic()
            delay(2_000)
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("TEST4 SMS Gateway", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF17231D))
            Text("بوابة إرسال رسائل التحقق", color = Color(0xFF63736A))

            StatusCard("حالة البوابة", if (workerRequested) "العامل يعمل" else "متوقفة",
                "التشغيل لا يفعّل إرسال SMS تلقائياً")
            StatusCard("آخر نتيجة", enrollmentStatus, workerDiagnostic)
            Button(
                onClick = if (workerRequested) onStopWorker else onStartWorker,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (workerRequested) "إيقاف البوابة" else "تشغيل البوابة") }
            Button(onClick = {
                onScanSim()
                onTestConnection()
            }, modifier = Modifier.fillMaxWidth()) { Text("فحص الاتصال") }
            val readySims = (simAvailability as? SimAvailability.Available)?.subscriptions
                ?.count { it.isReady } ?: 0
            Text("الشرائح الجاهزة: $readySims", color = Color(0xFF63736A))
            Text("إرسال الرسائل: " + if (smsTestOptIn) "مسموح محلياً للاختبار فقط" else "معطّل محلياً",
                color = Color(0xFF63736A))

            var showSetup by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            Text("إعداد الجهاز والتشخيص المتقدم", color = GatewayGreen,
                modifier = Modifier.clickable { showSetup = !showSetup })
            if (showSetup) {
                Text("إعداد أولي — لا تعِد إنشاء المفتاح إذا كان الجهاز مسجلاً",
                    color = Color(0xFF8A5A16))
                OutlinedTextField(value = serverUrl, onValueChange = onServerUrlChange,
                    label = { Text("عنوان الخادم HTTPS") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                StatusCard("تسجيل الجهاز", enrollmentStatus,
                    if (keyFingerprint.isEmpty()) "المفتاح الخاص يبقى داخل الهاتف"
                    else "بصمة SHA-256: $keyFingerprint")
                Text("يُنشئ التطبيق المعرّف وبصمة المفتاح تلقائياً ويحفظ المفتاح الخاص داخل Android Keystore.")
                Text("لا يلزم نسخ البيانات أثناء الاستخدام. تسجيل جهاز جديد بالخادم لا يزال يتطلب إجراءً آمناً من المسؤول.")
                Button(onClick = onProveDevice, modifier = Modifier.fillMaxWidth()) {
                    Text("إكمال إثبات الجهاز المسجّل")
                }
                Button(onClick = onToggleSmsTest, modifier = Modifier.fillMaxWidth()) {
                    Text(if (smsTestOptIn) "إلغاء تصريح اختبار SMS"
                         else "تصريح اختبار SMS المحلي — بعد موافقة المسؤول")
                }
                StatusCard("تشخيص العامل", workerDiagnostic, "تُحدّث النتيجة كل ثانيتين أثناء فتح الشاشة")
                Text("حالة الشرائح", fontWeight = FontWeight.SemiBold)
                when (val state = simAvailability) {
                    is SimAvailability.Available -> state.subscriptions.forEach { sim ->
                        StatusCard("SIM ${sim.slotIndex + 1} — ${sim.displayName}",
                            if (sim.isReady) "جاهزة" else "غير جاهزة",
                            if (sim.isDefaultForSms) "الشريحة الافتراضية" else "شريحة إضافية")
                    }
                    else -> Text("يجب منح إذن قراءة حالة الشرائح أو فحص توفرها")
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StatusCard(title: String, value: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = Color(0xFF64736B), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(detail, color = Color(0xFF7A8981), fontSize = 12.sp)
        }
    }
}

@androidx.compose.runtime.Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(label, color = Color(0xFF64736B), fontSize = 12.sp)
        }
    }
}
