package com.test4.smsgateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.test4.smsgateway.platform.telephony.AndroidSimManager
import com.test4.smsgateway.platform.telephony.SimAvailability

private val GatewayGreen = Color(0xFF16865B)
private val GatewayBackground = Color(0xFFF5F7F6)

class MainActivity : ComponentActivity() {
    private lateinit var simManager: AndroidSimManager
    private lateinit var identity: DeviceIdentity
    private var serverUrl by mutableStateOf(\"\")
    private var enrollmentStatus by mutableStateOf(\"لم يتم إنشاء مفتاح\")
    private var keyFingerprint by mutableStateOf(\"\")
    private var simAvailability by mutableStateOf<SimAvailability>(
        SimAvailability.PermissionRequired
    )

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
        serverUrl = getSharedPreferences(\"gateway_settings\", MODE_PRIVATE).getString(\"server_url\", \"\") ?: \"\"
        refreshSimStatus()

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
                    onServerUrlChange = { serverUrl = it },
                    onCreateIdentity = {
                        try {
                            identity.ensureKey()
                            keyFingerprint = identity.fingerprint()
                            enrollmentStatus = \"معرّف الجهاز: ${identity.deviceId} — المفتاح جاهز، ينتظر موافقة المسؤول\"
                        } catch (e: Exception) { enrollmentStatus = \"فشل إنشاء المفتاح: ${e.javaClass.simpleName}\" }
                    },
                    onCopyEnrollment = {
                        try {
                            val payload = \"Device ID: ${identity.deviceId}\\nPublic key PEM:\\n${identity.publicKeyPem()}\\nSHA-256: ${identity.fingerprint()}\"
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(\"Gateway enrollment\", payload))
                            enrollmentStatus = \"تم نسخ بيانات التسجيل العامة للمسؤول؛ لا تشاركها إلا عبر قناة موثوقة\"
                        } catch (e: Exception) { enrollmentStatus = \"أنشئ المفتاح أولاً\" }
                    },
                    onTestConnection = {
                        val url = serverUrl.trim().trimEnd('/')
                        if (!url.startsWith(\"https://\")) {
                            enrollmentStatus = \"يجب استخدام عنوان HTTPS موثوق\"
                        } else {
                            getSharedPreferences(\"gateway_settings\", MODE_PRIVATE).edit().putString(\"server_url\", url).apply()
                            enrollmentStatus = \"جارٍ فحص الاتصال...\"
                            Thread {
                                val result = try {
                                    MaqwatiGatewayApi(url, identity.deviceId, DeviceIdentity.ALIAS).heartbeat()
                                    \"الخادم متصل والجهاز معتمد\"
                                } catch (e: Exception) { \"لم يتم الاتصال أو لم يُعتمد الجهاز: ${e.javaClass.simpleName}\" }
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
    onServerUrlChange: (String) -> Unit,
    onCreateIdentity: () -> Unit,
    onTestConnection: () -> Unit,
    onCopyEnrollment: () -> Unit,
    onScanSim: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("TEST4 SMS Gateway", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF17231D))
            Text("بوابة إرسال رسائل التحقق", color = Color(0xFF63736A))

            StatusCard("تسجيل الجهاز", enrollmentStatus, if (keyFingerprint.isEmpty()) "المفتاح الخاص يبقى داخل الهاتف" else "بصمة المفتاح SHA-256: $keyFingerprint")
            Button(onClick = onCreateIdentity, modifier = Modifier.fillMaxWidth()) { Text("إنشاء مفتاح الجهاز الآمن") }
            Button(onClick = onCopyEnrollment, modifier = Modifier.fillMaxWidth()) { Text("نسخ المفتاح العام ومعرّف الجهاز للمسؤول") }
            OutlinedTextField(value = serverUrl, onValueChange = onServerUrlChange,
                label = { Text("عنوان الخادم HTTPS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = onTestConnection, modifier = Modifier.fillMaxWidth()) { Text("حفظ العنوان وفحص اعتماد الجهاز") }
            Text("الاتصال والشرائح", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            StatusCard("خادم مقتني لينك", "غير متصل حتى نجاح الفحص", "يتطلب تسجيل المفتاح العام وموافقة المسؤول على الخادم")

            when (val state = simAvailability) {
                SimAvailability.PermissionRequired -> StatusCard(
                    "الشريحة SIM 1",
                    "إذن الوصول مطلوب",
                    "اضغط فحص الشريحة واسمح للتطبيق بقراءة حالة الشريحة فقط"
                )
                SimAvailability.TelephonyUnavailable -> StatusCard(
                    "الشريحة SIM 1",
                    "خدمة الاتصالات غير متاحة",
                    "تعذر الوصول إلى خدمة شرائح الهاتف على هذا الجهاز"
                )
                is SimAvailability.Available -> {
                    if (state.subscriptions.isEmpty()) {
                        StatusCard("الشريحة SIM 1", "لم يتم العثور على شريحة", "تحقق من تركيب الشريحة وتفعيلها")
                    } else {
                        state.subscriptions.forEach { sim ->
                            StatusCard(
                                "SIM ${sim.slotIndex + 1} — ${sim.displayName}",
                                if (sim.isReady) "جاهزة" else "غير جاهزة",
                                if (sim.isDefaultForSms) "الشريحة الافتراضية للرسائل النصية"
                                else "معرّف الشريحة: ${sim.subscriptionId}"
                            )
                        }
                    }
                }
            }

            Button(onClick = onScanSim, modifier = Modifier.fillMaxWidth()) {
                Text("فحص الشريحة")
            }

            Text("إحصاءات الإرسال", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("مرسلة", "0", Modifier.weight(1f))
                MetricCard("فاشلة", "0", Modifier.weight(1f))
                MetricCard("معلّقة", "0", Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text("نسخة تأسيسية — لا تنفذ إرسال SMS حالياً", color = Color(0xFF8A5A16), fontSize = 12.sp)
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
