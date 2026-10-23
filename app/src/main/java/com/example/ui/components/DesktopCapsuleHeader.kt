package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TransferRecord
import com.example.data.model.TransferStatus
import com.example.data.network.SecureState

private val HeaderCapsuleShape = RoundedCornerShape(22.dp)

@Composable
fun DesktopCapsuleHeader(
    currentTransfer: TransferRecord?,
    onOpenHistory: () -> Unit,
    onCapsuleBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit,
    isBeingAttracted: Boolean,
    modifier: Modifier = Modifier,
    title: String = "AuraShare",
    secureState: SecureState? = null,
    onScanQr: () -> Unit = {},
    onConnectTrusted: (String) -> Unit = {},
    onDisconnectTrusted: () -> Unit = {},
    onForgetTrusted: (String) -> Unit = {},
) {
    val connected = secureState?.connected != null
    val isBusy = secureState?.busy == true
    val connectionStatus = when {
        connected -> "متصل بأمان — TLS 1.3"
        isBusy -> "جارٍ التحقق من الاتصال الآمن"
        secureState?.devices?.isNotEmpty() == true -> "أجهزة مرتبطة"
        else -> "جاهز للربط"
    }
    var statusExpanded by remember { mutableStateOf(false) }
    val pulse by rememberInfiniteTransition(label = "linked-device-pulse").animateFloat(
        .95f,
        1.05f,
        infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val onSurface = MaterialTheme.colorScheme.onSurface
    val transferLabel = if (currentTransfer?.status == TransferStatus.STREAMING) {
        ", جارٍ إرسال ${currentTransfer.item.name}، ${(currentTransfer.progress * 100).toInt()} بالمئة، ${currentTransfer.formattedSpeed}"
    } else ""
    val titleMaxWidth = (LocalConfiguration.current.screenWidthDp.dp - 164.dp).coerceAtLeast(76.dp)

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FloatingGlassSurface(
                modifier = Modifier
                    .widthIn(max = titleMaxWidth)
                    .onGloballyPositioned { onCapsuleBoundsChanged(it.boundsInRoot()) }
                    .semantics {
                        stateDescription = connectionStatus + transferLabel
                        liveRegion = LiveRegionMode.Polite
                    }
                    .testTag("desktop_capsule_status"),
                height = 44.dp,
                contentDescription = "$title، $connectionStatus$transferLabel",
                onClick = { statusExpanded = !statusExpanded },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(title, color = onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Box(Modifier.size(8.dp).scale(if (isBusy) pulse else 1f).clip(CircleShape).background(if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant))
                    Icon(if (connected) Icons.Default.Computer else Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
            FloatingDropdown(
                expanded = statusExpanded,
                onDismissRequest = { statusExpanded = false },
                modifier = Modifier.width(300.dp),
            ) {
                SecureDevicesPanel(
                    state = secureState,
                    onScanQr = { onScanQr(); statusExpanded = false },
                    onConnect = onConnectTrusted,
                    onDisconnect = onDisconnectTrusted,
                    onForget = onForgetTrusted,
                )
            }
        }

        FloatingGlassSurface(
            height = 44.dp,
            shape = HeaderCapsuleShape,
            modifier = Modifier.testTag("desktop_capsule_controls"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp).drawBehind {
                    drawLine(
                        Color.White.copy(alpha = .1f),
                        Offset(size.width / 2f, size.height * .3f),
                        Offset(size.width / 2f, size.height * .7f),
                        .5.dp.toPx(),
                    )
                },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderIconButton("transfer_history_button", "سجل عمليات النقل", Icons.Default.History, onOpenHistory)
            }
        }
    }
}

@Composable
private fun HeaderIconButton(tag: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp).testTag(tag)) {
        Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
    }
}
