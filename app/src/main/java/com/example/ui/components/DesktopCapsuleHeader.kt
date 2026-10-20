package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TransferRecord
import com.example.data.model.TransferStatus
import com.example.data.network.DiscoveryState
import com.example.ui.theme.GraphiteMuted
import com.example.ui.theme.GraphiteTrack
import com.example.ui.theme.MatteBorder
import com.example.ui.theme.MatteBorderSubtle
import com.example.ui.theme.MatteSurfaceCard
import com.example.ui.theme.MatteSurfaceElevated
import com.example.ui.theme.PlatinumPrimary
import com.example.ui.theme.SilverSecondary

@Composable
fun DesktopCapsuleHeader(
    discoveryState: DiscoveryState,
    currentTransfer: TransferRecord?,
    onRefreshDiscovery: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onCapsuleBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit,
    isBeingAttracted: Boolean,
    modifier: Modifier = Modifier
) {
    val isConnected = discoveryState is DiscoveryState.Connected
    val isSearching = discoveryState is DiscoveryState.Searching

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon-only Action: Refresh / Re-scan
            IconButton(
                onClick = onRefreshDiscovery,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MatteSurfaceCard)
                    .border(1.dp, MatteBorderSubtle, CircleShape)
                    .testTag("refresh_discovery_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "إعادة البحث عن الخادم",
                    tint = SilverSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Magnetic Absorption Desktop Capsule
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .onGloballyPositioned { coordinates ->
                        onCapsuleBoundsChanged(coordinates.boundsInRoot())
                    }
                    .scale(if (isBeingAttracted) 1.08f else 1f)
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        if (isConnected) MatteSurfaceElevated else MatteSurfaceCard
                    )
                    .border(
                        width = if (isBeingAttracted) 2.dp else 1.dp,
                        color = if (isBeingAttracted) PlatinumPrimary else MatteBorder,
                        shape = RoundedCornerShape(32.dp)
                    )
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("desktop_capsule_status"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Status icon node
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isConnected -> PlatinumPrimary
                                    isSearching -> PlatinumPrimary.copy(alpha = 0.4f)
                                    else -> GraphiteMuted
                                }
                            )
                            .scale(if (isSearching) pulseScale else 1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Icon(
                        imageVector = if (isConnected) Icons.Default.Computer else Icons.Default.Wifi,
                        contentDescription = null,
                        tint = if (isConnected) PlatinumPrimary else SilverSecondary,
                        modifier = Modifier.size(18.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Exact solid Arabic status copy required by prompt
                    Text(
                        text = when (discoveryState) {
                            is DiscoveryState.Connected -> "مُتصل بالحاسوب"
                            is DiscoveryState.Searching -> "جاري رصد الحاسوب..."
                            is DiscoveryState.Discovered -> "تم اكتشاف الحاسوب"
                            is DiscoveryState.Error -> "الخادم غير متصل"
                            DiscoveryState.Idle -> "جاهز للربط"
                        },
                        color = PlatinumPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Right Action Buttons (Icon-Only)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MatteSurfaceCard)
                        .border(1.dp, MatteBorderSubtle, CircleShape)
                        .testTag("manual_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsEthernet,
                        contentDescription = "إعدادات الربط اليدوي",
                        tint = SilverSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onOpenHistory,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MatteSurfaceCard)
                        .border(1.dp, MatteBorderSubtle, CircleShape)
                        .testTag("transfer_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "سجل عمليات النقل",
                        tint = SilverSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Live High-Speed Streaming Progress Banner inside Capsule Area
        if (currentTransfer != null && currentTransfer.status == TransferStatus.STREAMING) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MatteSurfaceCard)
                    .border(1.dp, MatteBorderSubtle, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = currentTransfer.item.name,
                    color = SilverSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = currentTransfer.formattedSpeed,
                    color = PlatinumPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { currentTransfer.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = PlatinumPrimary,
                trackColor = GraphiteTrack
            )
        }
    }
}
