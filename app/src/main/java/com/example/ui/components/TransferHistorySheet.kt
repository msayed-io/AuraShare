package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.MaterialTheme
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
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        FloatingBackdropHost(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.85f),
            content = {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 80.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().testTag("transfer_history_list")
                ) {
                    if (transfers.isEmpty()) {
                        item {
                            Text(
                                "لم يتم إرسال أي ملفات خلال هذه الجلسة",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 40.dp)
                            )
                        }
                    } else {
                        items(transfers, key = { it.id }) { TransferHistoryItem(it) }
                    }
                }
            },
            overlay = {
                FloatingTopDissolve()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FloatingGlassSurface(modifier = Modifier.widthIn(max = 240.dp)) {
                        Text(
                            "سجل النقل",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                    CircularBackButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_history_sheet_button"),
                        contentDescription = "العودة وإغلاق سجل النقل"
                    )
                }
            }
        )
    }
}

@Composable
private fun TransferHistoryItem(record: TransferRecord) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
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
                        TransferStatus.COMPLETED -> MaterialTheme.colorScheme.onSurface
                        TransferStatus.STREAMING -> MaterialTheme.colorScheme.onSurface
                        TransferStatus.FAILED -> MaterialTheme.colorScheme.onSurfaceVariant
                        TransferStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.item.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(
                        text = record.item.formattedSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    if (record.speedBytesPerSec > 0) {
                        Text(
                            text = " • ${record.formattedSpeed}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = if (record.status == TransferStatus.COMPLETED) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
