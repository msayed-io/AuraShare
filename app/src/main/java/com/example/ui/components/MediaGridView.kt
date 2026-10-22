package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem

@Composable
fun MediaGridView(
    mediaType: AuraMediaType,
    items: List<MediaItem>,
    dragController: FluidDragController,
    onQuickSend: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 12.dp, end = 12.dp, top = 80.dp, bottom = 96.dp),
    header: @Composable () -> Unit = {}
) {
    if (items.isEmpty()) {
        LazyColumn(modifier = modifier.fillMaxSize().testTag("empty_media_list"), contentPadding = contentPadding) {
            item(key = "media_header") { header() }
            item(key = "empty_state") {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (mediaType) {
                            AuraMediaType.IMAGE -> Icons.Default.Description
                            AuraMediaType.VIDEO -> Icons.Default.Videocam
                            AuraMediaType.FILE -> Icons.Default.FolderZip
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "لا توجد ملفات متوفرة في هذا المجلد",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }
        }
        }
        return
    }

    when (mediaType) {
        AuraMediaType.IMAGE -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = modifier.fillMaxSize().testTag("images_grid")
            ) {
                item(key = "media_header", span = { GridItemSpan(maxLineSpan) }) { header() }
                items(items, key = { "img_${it.id}" }) { item ->
                    ImageGridItem(
                        item = item,
                        dragController = dragController,
                        onQuickSend = onQuickSend
                    )
                }
            }
        }
        AuraMediaType.VIDEO -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = modifier.fillMaxSize().testTag("videos_grid")
            ) {
                item(key = "media_header", span = { GridItemSpan(maxLineSpan) }) { header() }
                items(items, key = { "vid_${it.id}" }) { item ->
                    VideoGridItem(
                        item = item,
                        dragController = dragController,
                        onQuickSend = onQuickSend
                    )
                }
            }
        }
        AuraMediaType.FILE -> {
            LazyColumn(
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = modifier.fillMaxSize().testTag("files_list")
            ) {
                item(key = "media_header") { header() }
                items(items, key = { "file_${it.id}" }) { item ->
                    FileListRowItem(
                        item = item,
                        dragController = dragController,
                        onQuickSend = onQuickSend
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageGridItem(
    item: MediaItem,
    dragController: FluidDragController,
    onQuickSend: (MediaItem) -> Unit
) {
    var itemRootOffset by remember { mutableStateOf(Offset.Zero) }
    val isBeingDragged = dragController.activeItem?.id == item.id

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onGloballyPositioned { coordinates ->
                itemRootOffset = coordinates.positionInRoot()
            }
            .fluidDraggable(
                item = item,
                getItemRootOffset = { itemRootOffset },
                dragController = dragController
            )
            .semantics {
                customActions = listOf(CustomAccessibilityAction("إرسال إلى الحاسوب") { onQuickSend(item); true })
            }
            .testTag("media_item_${item.id}")
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (isBeingDragged) 0.25f else 1f)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.uri)
                    .crossfade(true)
                    .build(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Subtle size pill
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = item.formattedSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun VideoGridItem(
    item: MediaItem,
    dragController: FluidDragController,
    onQuickSend: (MediaItem) -> Unit
) {
    var itemRootOffset by remember { mutableStateOf(Offset.Zero) }
    val isBeingDragged = dragController.activeItem?.id == item.id

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f)
            .onGloballyPositioned { coordinates ->
                itemRootOffset = coordinates.positionInRoot()
            }
            .fluidDraggable(
                item = item,
                getItemRootOffset = { itemRootOffset },
                dragController = dragController
            )
            .semantics {
                customActions = listOf(CustomAccessibilityAction("إرسال إلى الحاسوب") { onQuickSend(item); true })
            }
            .testTag("video_item_${item.id}")
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (isBeingDragged) 0.25f else 1f)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.uri)
                    .decoderFactory(VideoFrameDecoder.Factory())
                    .crossfade(true)
                    .build(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Play icon overlay
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Top duration overlay capsule
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = item.formattedDuration,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Bottom size pill
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = item.formattedSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun FileListRowItem(
    item: MediaItem,
    dragController: FluidDragController,
    onQuickSend: (MediaItem) -> Unit
) {
    var itemRootOffset by remember { mutableStateOf(Offset.Zero) }
    val isBeingDragged = dragController.activeItem?.id == item.id

    val icon: ImageVector = when {
        item.name.endsWith(".pdf", ignoreCase = true) -> Icons.Default.PictureAsPdf
        item.name.endsWith(".zip", ignoreCase = true) || item.name.endsWith(".rar", ignoreCase = true) -> Icons.Default.Archive
        item.name.endsWith(".json", ignoreCase = true) || item.name.endsWith(".kt", ignoreCase = true) -> Icons.Default.Code
        else -> Icons.Default.Description
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .onGloballyPositioned { coordinates ->
                itemRootOffset = coordinates.positionInRoot()
            }
            .fluidDraggable(
                item = item,
                getItemRootOffset = { itemRootOffset },
                dragController = dragController
            )
            .testTag("file_item_${item.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
                .alpha(if (isBeingDragged) 0.25f else 1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Document Type Icon
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.formattedSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            // Icon-only send affordance
            IconButton(
                onClick = { onQuickSend(item) },
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "إرسال الملف للحاسوب",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
