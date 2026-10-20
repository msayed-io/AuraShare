package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.PairingUriParser
import com.example.ui.theme.GraphiteMuted
import com.example.ui.theme.MatteBorder
import com.example.ui.theme.MatteBorderSubtle
import com.example.ui.theme.MatteSurfaceCard
import com.example.ui.theme.MatteSurfaceElevated
import com.example.ui.theme.PlatinumPrimary
import com.example.ui.theme.SilverSecondary

@Composable
fun ManualServerDialog(
    initialHost: String,
    initialPort: Int,
    initialToken: String? = null,
    initialLegacy: Boolean = false,
    onDismiss: () -> Unit,
    onConnect: (String, Int, String?, Boolean) -> Unit,
    onImportUri: (String) -> Boolean
) {
    var hostText by remember { mutableStateOf(initialHost.ifEmpty { "192.168.1." }) }
    var portText by remember { mutableStateOf(if (initialPort > 0) initialPort.toString() else "54321") }
    var tokenText by remember { mutableStateOf(initialToken.orEmpty()) }
    var legacyMode by remember { mutableStateOf(initialLegacy) }
    var uriInputText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MatteSurfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, MatteBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("manual_server_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Icon Badge
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MatteSurfaceElevated)
                        .border(1.dp, MatteBorderSubtle, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = null,
                        tint = PlatinumPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "الاقتران وإعدادات خادم AuraShare",
                    color = PlatinumPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "نقل مباشر عبر الشبكة المحلية (غير مشفر). أدخل بيانات الخادم أو الصق رابط الاقتران.",
                    color = SilverSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Fast Import via URL: aurashare://pair?host=...&port=...&token=...
                OutlinedTextField(
                    value = uriInputText,
                    onValueChange = {
                        uriInputText = it
                        importError = null
                    },
                    label = { Text("استيراد عبر رابط aurashare://pair") },
                    placeholder = { Text("aurashare://pair?host=...&port=...&token=...", fontSize = 11.sp) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (uriInputText.isNotBlank()) {
                                    val parsed = PairingUriParser.parse(uriInputText)
                                    if (parsed != null) {
                                        hostText = parsed.host
                                        portText = parsed.port.toString()
                                        tokenText = parsed.token.orEmpty()
                                        legacyMode = parsed.useLegacyUpload
                                        onImportUri(uriInputText)
                                        onDismiss()
                                    } else {
                                        importError = "صيغة الرابط غير صحيحة"
                                    }
                                }
                            },
                            modifier = Modifier.testTag("import_uri_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = "استيراد الرابط",
                                tint = PlatinumPrimary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PlatinumPrimary,
                        unfocusedBorderColor = MatteBorder,
                        focusedTextColor = PlatinumPrimary,
                        unfocusedTextColor = SilverSecondary,
                        focusedContainerColor = MatteSurfaceElevated,
                        unfocusedContainerColor = MatteSurfaceElevated
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("import_uri_field")
                )

                if (importError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = importError!!,
                        color = androidx.compose.ui.graphics.Color(0xFFFF6B6B),
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Host Field
                OutlinedTextField(
                    value = hostText,
                    onValueChange = { hostText = it },
                    label = { Text("عنوان IP الخادم (Host)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PlatinumPrimary,
                        unfocusedBorderColor = MatteBorder,
                        focusedTextColor = PlatinumPrimary,
                        unfocusedTextColor = SilverSecondary,
                        focusedContainerColor = MatteSurfaceElevated,
                        unfocusedContainerColor = MatteSurfaceElevated
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("host_input_field")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Port Field
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("المنفذ (Port)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PlatinumPrimary,
                        unfocusedBorderColor = MatteBorder,
                        focusedTextColor = PlatinumPrimary,
                        unfocusedTextColor = SilverSecondary,
                        focusedContainerColor = MatteSurfaceElevated,
                        unfocusedContainerColor = MatteSurfaceElevated
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("port_input_field")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Pairing Token Field
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    label = { Text("رمز الاقتران (X-AuraShare-Token)") },
                    placeholder = { Text("أدخل الرمز المعروض على الحاسوب", fontSize = 11.sp) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = SilverSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PlatinumPrimary,
                        unfocusedBorderColor = MatteBorder,
                        focusedTextColor = PlatinumPrimary,
                        unfocusedTextColor = SilverSecondary,
                        focusedContainerColor = MatteSurfaceElevated,
                        unfocusedContainerColor = MatteSurfaceElevated
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("token_input_field")
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Legacy Mode Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MatteSurfaceElevated)
                        .border(1.dp, MatteBorderSubtle, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (legacyMode) "وضع التوافق القديم (/api/upload)" else "المسار الحديث (/api/v1/upload)",
                            color = PlatinumPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (legacyMode) "يستخدم form-encoding للتوافق مع الإصدارات السابقة" else "المسار الافتراضي الموصى به مع التحقق الأمني",
                            color = SilverSecondary,
                            fontSize = 10.sp
                        )
                    }

                    Switch(
                        checked = legacyMode,
                        onCheckedChange = { legacyMode = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PlatinumPrimary,
                            checkedTrackColor = MatteBorder,
                            uncheckedThumbColor = GraphiteMuted,
                            uncheckedTrackColor = MatteSurfaceCard
                        ),
                        modifier = Modifier.testTag("legacy_mode_toggle")
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MatteSurfaceElevated)
                            .border(1.dp, MatteBorderSubtle, CircleShape)
                            .testTag("dismiss_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إلغاء",
                            tint = SilverSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val parsedPort = portText.trim().toIntOrNull() ?: 54321
                            val cleanToken = tokenText.trim().ifEmpty { null }
                            onConnect(hostText.trim(), parsedPort, cleanToken, legacyMode)
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(PlatinumPrimary)
                            .testTag("confirm_connect_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "تأكيد والاتصال",
                            tint = MatteSurfaceCard,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
