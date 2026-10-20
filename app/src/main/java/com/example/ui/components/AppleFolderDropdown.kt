package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.ui.theme.GraphiteMuted
import com.example.ui.theme.MatteBorder
import com.example.ui.theme.MatteSurfaceCard
import com.example.ui.theme.MatteSurfaceElevated
import com.example.ui.theme.PlatinumPrimary
import com.example.ui.theme.SilverSecondary

data class FolderOption(
    val name: String,
    val count: Int,
    val icon: ImageVector = Icons.Outlined.Folder
)

@Composable
fun AppleFolderDropdown(
    folders: List<FolderOption>,
    selectedFolder: String,
    onFolderSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // Icon-only trigger button conforming to strict no-text policy
        IconButton(
            onClick = { isExpanded = !isExpanded },
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (isExpanded) MatteSurfaceElevated else MatteSurfaceCard)
                .border(1.dp, MatteBorder, CircleShape)
                .testTag("folder_dropdown_button")
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = "اختيار المجلد المحلي",
                tint = PlatinumPrimary,
                modifier = Modifier.size(20.dp)
            )
        }

        // Apple Dropdown Philosophy: Absolute Floating Popup Overlay
        if (isExpanded) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(x = 0, y = 120),
                onDismissRequest = { isExpanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Column(
                    modifier = Modifier
                        .width(250.dp) // Fixed width strictly 250dp
                        .shadow(16.dp, RoundedCornerShape(18.dp))
                        .clip(RoundedCornerShape(18.dp))
                        .background(MatteSurfaceElevated)
                        .border(1.dp, MatteBorder, RoundedCornerShape(18.dp))
                        .padding(vertical = 6.dp)
                ) {
                    folders.forEach { folder ->
                        val isSelected = folder.name == selectedFolder

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp) // Strict 44dp item height
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) MatteSurfaceCard else MatteSurfaceElevated
                                )
                                .clickable {
                                    onFolderSelect(folder.name)
                                    isExpanded = false
                                }
                                .padding(horizontal = 12.dp)
                                .testTag("folder_item_${folder.name}")
                        ) {
                            // Uniform 24dp slot magnetically aligned
                            Box(
                                modifier = Modifier.size(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = folder.icon,
                                    contentDescription = null,
                                    tint = if (isSelected) PlatinumPrimary else SilverSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            // Strict 1-2 words with text-overflow ellipsis
                            Text(
                                text = folder.name,
                                color = if (isSelected) PlatinumPrimary else SilverSecondary,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = PlatinumPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Text(
                                    text = folder.count.toString(),
                                    color = GraphiteMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Light
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
