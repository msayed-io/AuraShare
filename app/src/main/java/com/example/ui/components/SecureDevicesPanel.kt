package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.network.SecureState

@Composable
fun SecureDevicesPanel(
    state: SecureState?,
    onScanQr: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onForget: (String) -> Unit,
) {
    var forgetId by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
        Text("الأجهزة الموثوقة", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("امسح QR من الكمبيوتر مرة واحدة، ثم وافق على الكمبيوتر. الاتصال التالي بزر فقط.", fontSize = 11.sp)
        Text("يتطلب الاقتران الآمن إصدار الكمبيوتر الداعم لـQR وTLS؛ الإصدار القديم لا يدعمه.", fontSize = 11.sp)
        TextButton(onClick = onScanQr, enabled = state?.busy != true && state?.trustAvailable != false, modifier = Modifier.testTag("scan_secure_qr")) {
            Text("مسح QR للاقتران الآمن")
        }
        state?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.testTag("secure_error")) }
        if (state?.busy == true) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("جارٍ التحقق والاتصال؛ عند أول اقتران وافق على الكمبيوتر", fontSize = 11.sp)
        }
        state?.devices?.forEach { device ->
            val connected = state.connected?.device?.id == device.id
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("trusted_${device.id}")) {
                Text(device.name, fontWeight = FontWeight.SemiBold)
                Text(if (connected) "متصل بأمان — TLS 1.3" else "محفوظ — غير متصل", fontSize = 11.sp)
                Row {
                    TextButton(onClick = { if (connected) onDisconnect() else onConnect(device.id) }, enabled = !state.busy) {
                        Text(if (connected) "قطع الاتصال" else "اتصال")
                    }
                    TextButton(onClick = { forgetId = device.id }, enabled = !state.busy) { Text("إلغاء الثقة") }
                }
            }
        }
        if (state != null && state.devices.isEmpty()) Text("لا توجد أجهزة موثوقة محفوظة بعد.", fontSize = 11.sp)
    }
    forgetId?.let { id ->
        AlertDialog(
            onDismissRequest = { forgetId = null },
            title = { Text("إلغاء ثقة الجهاز؟") },
            text = { Text("سيُحذف اعتماد الجهاز من هذا الهاتف. سنحاول إلغاءه على الكمبيوتر أيضًا؛ إذا كان غير متاح فقد تحتاج إزالته من الكمبيوتر لاحقًا. ستحتاج QR وموافقة جديدة لإعادة الاقتران.") },
            confirmButton = { TextButton(onClick = { forgetId = null; onForget(id) }) { Text("إلغاء الثقة") } },
            dismissButton = { TextButton(onClick = { forgetId = null }) { Text("رجوع") } },
        )
    }
}
