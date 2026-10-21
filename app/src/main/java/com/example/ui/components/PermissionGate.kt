package com.example.ui.components

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MatteBorder
import com.example.ui.theme.MatteBorderSubtle
import com.example.ui.theme.MatteSurfaceCard
import com.example.ui.theme.MatteSurfaceElevated
import com.example.ui.theme.PlatinumPrimary
import com.example.ui.theme.SilverSecondary

@Composable
fun PermissionGate(
    isMediaPermissionGranted: Boolean,
    onPermissionsUpdated: () -> Unit,
    onPickFolder: () -> Unit,
    onPickerError: (String) -> Unit = {},
    content: @Composable (pickFolder: () -> Unit) -> Unit
) {
    var hasBypassedPermission by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val mediaPermissions = if (Build.VERSION.SDK_INT >= 34) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onPermissionsUpdated()
        hasBypassedPermission = true
    }
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                // OpenDocumentTree grants persistability; takePersistableUriPermission accepts READ only.
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onSuccess {
                hasBypassedPermission = true
                onPickFolder()
                onPermissionsUpdated()
            }.onFailure { error ->
                onPickerError("تعذر حفظ صلاحية المجلد: ${error.message ?: "رفض مزود الملفات الطلب"}")
            }
        }
    }

    val pickFolder: () -> Unit = { folderPickerLauncher.launch(null) }

    val hasFolderAccess = context.contentResolver.persistedUriPermissions.any { it.isReadPermission }
    if (isMediaPermissionGranted || hasBypassedPermission || hasFolderAccess) {
        content(pickFolder)
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MatteSurfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, MatteBorder),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("permission_gate_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Circular Icon Node
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MatteSurfaceElevated)
                        .border(1.dp, MatteBorderSubtle, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PermMedia,
                        contentDescription = null,
                        tint = PlatinumPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "الوصول إلى وسائط الجهاز",
                    color = PlatinumPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "يتطلب تطبيق AuraShare قراءة الصور والمقاطع لمشاركتها فورياً وبأعلى سرعة مع الحاسوب عبر الشبكة المحلية.",
                    color = SilverSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Actions row with icon-only buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Continue to accessible files without granting photo/video access.
                    IconButton(
                        onClick = { hasBypassedPermission = true },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MatteSurfaceElevated)
                            .border(1.dp, MatteBorderSubtle, CircleShape)
                            .testTag("skip_permission_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "استعراض الملفات والمجلدات",
                            tint = SilverSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Primary: Grant permission
                    IconButton(
                        onClick = {
                            mediaPermissionLauncher.launch(mediaPermissions)
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(PlatinumPrimary)
                            .testTag("grant_permission_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "منح التصريح للمتابعة",
                            tint = MatteSurfaceCard,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
