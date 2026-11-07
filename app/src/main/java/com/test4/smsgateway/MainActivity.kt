package com.test4.smsgateway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val GatewayGreen = Color(0xFF16865B)
private val GatewayBackground = Color(0xFFF5F7F6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = GatewayGreen,
                    background = GatewayBackground,
                    surface = Color.White
                )
            ) {
                GatewayDashboard()
            }
        }
    }
}

@Composable
private fun GatewayDashboard() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("TEST4 SMS Gateway", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF17231D))
            Text("بوابة إرسال رسائل التحقق", color = Color(0xFF63736A))

            StatusCard("حالة البوابة", "غير مفعّلة", "لم يتم تسجيل الجهاز أو ربطه بالخادم بعد")
            Text("الاتصال والشرائح", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            StatusCard("خادم TEST4", "غير متصل", "سيتم إعداد HTTPS Polling بعد اعتماد عقد API")
            StatusCard("SIM 1 — الشريحة الأساسية", "لم يتم الفحص", "الجهاز المستهدف: Samsung Galaxy Note 10")

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

@Composable
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

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(label, color = Color(0xFF64736B), fontSize = 12.sp)
        }
    }
}
