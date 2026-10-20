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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TransferRecord
import com.example.data.model.TransferStatus
import com.example.ui.theme.GraphiteMuted
import com.example.ui.theme.MatteBackground
import com.example.ui.theme.MatteBorder
import com.example.ui.theme.MatteBorderSubtle
import com.example.ui.theme.MatteSurfaceCard
import com.example.ui.theme.MatteSurfaceElevated
import com.example.ui.theme.PlatinumPrimary
import com.example.ui.theme.SilverSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferHistorySheet(
    transfers: List<TransferRecord>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MatteSurfaceCard,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "سجل دفق الملفات اللاسلكي",
                    color = PlatinumPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MatteSurfaceElevated)
                        .border(1.dp, MatteBorderSubtle, CircleShape)
                        .testTag("close_history_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "إغلاق السجل",
                        tint = SilverSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (transfers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "لم يتم إرسال أي ملفات خلال هذه الجلسة",
                        color = GraphiteMuted,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    items(transfers, key = { it.id }) { record ->
                        TransferHistoryItem(record = record)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferHistoryItem(record: TransferRecord) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MatteSurfaceElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, MatteBorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MatteSurfaceCard)
                    .border(1.dp, MatteBorderSubtle, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (record.status) {
                        TransferStatus.COMPLETED -> Icons.Default.CheckCircle
                        TransferStatus.STREAMING -> Icons.Default.Sync
                        TransferStatus.FAILED -> Icons.Default.Error
                        TransferStatus.QUEUED -> Icons.Default.HourglassTop
                    },
                    contentDescription = null,
                    tint = when (record.status) {
                        TransferStatus.COMPLETED -> PlatinumPrimary
                        TransferStatus.STREAMING -> PlatinumPrimary
                        TransferStatus.FAILED -> SilverSecondary
                        TransferStatus.QUEUED -> GraphiteMuted
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.item.name,
                    color = PlatinumPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(
                        text = record.item.formattedSize,
                        color = GraphiteMuted,
                        fontSize = 12.sp
                    )
                    if (record.speedBytesPerSec > 0) {
                        Text(
                            text = " • ${record.formattedSpeed}",
                            color = SilverSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Text(
                text = when (record.status) {
                    TransferStatus.COMPLETED -> "مكتمل"
                    TransferStatus.STREAMING -> "${(record.progress * 100).toInt()}%"
                    TransferStatus.FAILED -> "تعذر النقل"
                    TransferStatus.QUEUED -> "في الانتظار"
                },
                color = if (record.status == TransferStatus.COMPLETED) PlatinumPrimary else SilverSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
