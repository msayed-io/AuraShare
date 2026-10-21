package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TransferRecord
import com.example.data.model.TransferStatus
import com.example.data.network.DiscoveryState
import com.example.data.network.NearbyPeer
import com.example.data.network.PeerStatus

private val HeaderCapsuleShape = RoundedCornerShape(22.dp)

@Composable
fun DesktopCapsuleHeader(
    discoveryState: DiscoveryState,
    currentTransfer: TransferRecord?,
    onRefreshDiscovery: () -> Unit,
    onPair: (NearbyPeer) -> Unit,
    onDisconnect: (NearbyPeer) -> Unit,
    onOpenHistory: () -> Unit,
    onCapsuleBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit,
    isBeingAttracted: Boolean,
    modifier: Modifier = Modifier,
    title: String = "AuraShare",
) {
    val nearby = (discoveryState as? DiscoveryState.Nearby)?.peers.orEmpty()
    val isConnected = discoveryState is DiscoveryState.Connected || nearby.any { it.status == PeerStatus.CONNECTED }
    val isSearching = discoveryState is DiscoveryState.Searching
    var statusExpanded by remember { mutableStateOf(false) }
    var actionsExpanded by remember { mutableStateOf(false) }
    val pulse by rememberInfiniteTransition(label = "nearby-pulse").animateFloat(
        .95f, 1.05f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse"
    )
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val transferLabel = if (currentTransfer?.status == TransferStatus.STREAMING)
        "، جارٍ إرسال ${currentTransfer.item.name}، ${(currentTransfer.progress * 100).toInt()} بالمئة، ${currentTransfer.formattedSpeed}" else ""
    val titleMaxWidth = (LocalConfiguration.current.screenWidthDp.dp - 164.dp).coerceAtLeast(76.dp)

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // In RTL this first child is the physical right edge: identity + status icon only.
        Box {
            FloatingGlassSurface(
                modifier = Modifier
                    .widthIn(max = titleMaxWidth)
                    .onGloballyPositioned { onCapsuleBoundsChanged(it.boundsInRoot()) }
                    .semantics {
                        stateDescription = statusLabel(discoveryState, isConnected) + transferLabel
                        liveRegion = LiveRegionMode.Polite
                    }
                    .testTag("desktop_capsule_status"),
                height = 44.dp,
                contentDescription = "$title، ${statusLabel(discoveryState, isConnected)}$transferLabel",
                onClick = { statusExpanded = !statusExpanded },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(title, color = onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Box(Modifier.size(8.dp).scale(if (isSearching) pulse else 1f).clip(CircleShape).background(if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant))
                    Icon(if (isConnected) Icons.Default.Computer else Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
            FloatingDropdown(
                expanded = statusExpanded,
                onDismissRequest = { statusExpanded = false },
                modifier = Modifier.width(300.dp),
            ) {
                NearbyConnectionDetails(
                    discoveryState = discoveryState,
                    currentTransfer = currentTransfer,
                    onPair = { onPair(it); statusExpanded = false },
                    onDisconnect = { onDisconnect(it); statusExpanded = false },
                )
            }
        }

        // In RTL this group is the physical left edge: a single 44dp controls capsule.
        FloatingGlassSurface(height = 44.dp, shape = HeaderCapsuleShape, modifier = Modifier.testTag("desktop_capsule_controls")) {
            Row(modifier = Modifier.padding(horizontal = 4.dp).drawBehind {
                val separator = Color.White.copy(alpha = .1f)
                listOf(1f / 3f, 2f / 3f).forEach { fraction ->
                    drawLine(separator, Offset(size.width * fraction, size.height * .3f), Offset(size.width * fraction, size.height * .7f), .5.dp.toPx())
                }
            }, horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderIconButton("refresh_discovery_button", "إعادة البحث عن الخادم", Icons.Default.Refresh, onRefreshDiscovery)
                HeaderIconButton("transfer_history_button", "سجل عمليات النقل", Icons.Default.History, onOpenHistory)
                Box {
                    HeaderIconButton("header_more_button", "المزيد من الإجراءات", Icons.Default.MoreVert, { actionsExpanded = true })
                    FloatingDropdown(
                        expanded = actionsExpanded,
                        onDismissRequest = { actionsExpanded = false },
                        modifier = Modifier.width(188.dp),
                    ) {
                        HeaderMenuItem(Icons.Default.Refresh, "إعادة البحث", onRefreshDiscovery) { actionsExpanded = false }
                        HeaderMenuItem(Icons.Default.History, "سجل النقل", onOpenHistory) { actionsExpanded = false }
                    }
                }
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

@Composable
private fun HeaderMenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, close: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(16.dp)).floatingMenuInteraction { onClick(); close() }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}

@Composable
fun NearbyConnectionDetails(
    discoveryState: DiscoveryState,
    currentTransfer: TransferRecord?,
    onPair: (NearbyPeer) -> Unit,
    onDisconnect: (NearbyPeer) -> Unit,
) {
    val nearby = (discoveryState as? DiscoveryState.Nearby)?.peers.orEmpty()
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("الاتصال القريب", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        nearby.forEach { peer ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(10.dp)) {
                Text(peer.deviceName ?: "اسم الكمبيوتر غير متاح", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                Text("العنوان الفعلي: ${peer.host}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text(peerStatusLabel(peer.status), color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (peer.status == PeerStatus.CONNECTED) "قطع الاتصال" else if (peer.status == PeerStatus.PAIRING) "بانتظار الموافقة" else "اقتران",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = peer.status != PeerStatus.PAIRING) { if (peer.status == PeerStatus.CONNECTED) onDisconnect(peer) else onPair(peer) }.padding(horizontal = 10.dp, vertical = 8.dp).testTag(if (peer.status == PeerStatus.CONNECTED) "disconnect_${peer.serviceName}" else "pair_${peer.serviceName}"),
                    fontSize = 12.sp,
                )
            }
        }
        if (nearby.isEmpty()) Text(statusLabel(discoveryState, false), color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
        if (discoveryState is DiscoveryState.Error) Text(discoveryState.message, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
        Text("على نفس الشبكة الخاصة؛ النقل غير مشفّر وأسماء الأجهزة غير موثّقة.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        if (currentTransfer?.status == TransferStatus.STREAMING) {
            Text(currentTransfer.item.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, maxLines = 1)
            Text(currentTransfer.formattedSpeed, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            LinearProgressIndicator({ currentTransfer.progress }, Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

private fun statusLabel(state: DiscoveryState, connected: Boolean) = when (state) {
    is DiscoveryState.Connected -> "مُتصل بالحاسوب"
    is DiscoveryState.Searching -> "جاري رصد الحاسوب..."
    is DiscoveryState.Discovered -> "تم اكتشاف الحاسوب"
    is DiscoveryState.Error -> "الخادم غير متصل"
    is DiscoveryState.Nearby -> if (connected) "مُتصل بالحاسوب" else "حواسيب قريبة"
    DiscoveryState.Idle -> "جاهز للربط"
}

private fun peerStatusLabel(status: PeerStatus) = when (status) {
    PeerStatus.CONNECTED -> "متصل وجاهز للإرسال"
    PeerStatus.PAIRING -> "بانتظار موافقتك على الكمبيوتر"
    PeerStatus.DISCONNECTED -> "انقطع الاتصال؛ يمكنك إعادة الاقتران"
    PeerStatus.NEARBY -> "متاح للاقتران"
}
