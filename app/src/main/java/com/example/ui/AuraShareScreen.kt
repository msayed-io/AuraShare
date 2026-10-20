package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AuraMediaType
import com.example.ui.components.AppleFolderDropdown
import com.example.ui.components.DesktopCapsuleHeader
import com.example.ui.components.FluidMetaballContainer
import com.example.ui.components.ManualServerDialog
import com.example.ui.components.MediaGridView
import com.example.ui.components.PermissionGate
import com.example.ui.components.TransferHistorySheet
import com.example.ui.components.rememberFluidDragController
import com.example.ui.theme.MatteBackground
import com.example.ui.theme.MatteBorder
import com.example.ui.theme.MatteBorderSubtle
import com.example.ui.theme.MatteSurfaceCard
import com.example.ui.theme.MatteSurfaceElevated
import com.example.ui.theme.PlatinumPrimary
import com.example.ui.theme.SilverSecondary

@Composable
fun AuraShareScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val discoveryState by viewModel.discoveryState.collectAsState()
    val currentTransfer by viewModel.currentTransfer.collectAsState()
    val activeTransfers by viewModel.activeTransfers.collectAsState()

    var capsuleBounds by remember { mutableStateOf<Rect?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val dragController = rememberFluidDragController(
        onSendItem = { item -> viewModel.sendItem(item) }
    )

    LaunchedEffect(uiState.statusNotification) {
        uiState.statusNotification?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearNotification()
        }
    }

    PermissionGate(
        isMediaPermissionGranted = uiState.isMediaPermissionGranted,
        onPermissionsUpdated = { viewModel.checkPermissions() }
    ) {
        FluidMetaballContainer(
            dragController = dragController
        ) {
            Scaffold(
                containerColor = MatteBackground,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        // Desktop Capsule & Global Action Bar
                        DesktopCapsuleHeader(
                            discoveryState = discoveryState,
                            currentTransfer = currentTransfer,
                            onRefreshDiscovery = { viewModel.refreshDiscovery() },
                            onOpenSettings = { viewModel.showManualServerDialog(true) },
                            onOpenHistory = { viewModel.showHistorySheet(true) },
                            onCapsuleBoundsChanged = { bounds -> capsuleBounds = bounds },
                            isBeingAttracted = dragController.isAttracted
                        )

                        // Sub-header with Apple Folder Dropdown and Context info
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (uiState.selectedTab) {
                                    AuraMediaType.IMAGE -> "معرض الصور"
                                    AuraMediaType.VIDEO -> "مقاطع الفيديو"
                                    AuraMediaType.FILE -> "المستندات والملفات"
                                },
                                color = PlatinumPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            // Apple Dropdown Philosophy folder selector
                            AppleFolderDropdown(
                                folders = uiState.availableFolders,
                                selectedFolder = uiState.selectedFolder,
                                onFolderSelect = { viewModel.selectFolder(it) }
                            )
                        }
                    }
                },
                bottomBar = {
                    // Refined Minimalist Icon-Only Tab Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(32.dp))
                                .background(MatteSurfaceCard)
                                .border(1.dp, MatteBorder, RoundedCornerShape(32.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Tab 1: Images (Icon-Only)
                            IconButton(
                                onClick = { viewModel.selectTab(AuraMediaType.IMAGE) },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (uiState.selectedTab == AuraMediaType.IMAGE) MatteSurfaceElevated else MatteSurfaceCard
                                    )
                                    .border(
                                        1.dp,
                                        if (uiState.selectedTab == AuraMediaType.IMAGE) MatteBorder else MatteBorderSubtle,
                                        CircleShape
                                    )
                                    .testTag("tab_images_button")
                            ) {
                                Icon(
                                    imageVector = if (uiState.selectedTab == AuraMediaType.IMAGE) Icons.Filled.Image else Icons.Outlined.Image,
                                    contentDescription = "تبويب الصور",
                                    tint = if (uiState.selectedTab == AuraMediaType.IMAGE) PlatinumPrimary else SilverSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Tab 2: Videos (Icon-Only)
                            IconButton(
                                onClick = { viewModel.selectTab(AuraMediaType.VIDEO) },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (uiState.selectedTab == AuraMediaType.VIDEO) MatteSurfaceElevated else MatteSurfaceCard
                                    )
                                    .border(
                                        1.dp,
                                        if (uiState.selectedTab == AuraMediaType.VIDEO) MatteBorder else MatteBorderSubtle,
                                        CircleShape
                                    )
                                    .testTag("tab_videos_button")
                            ) {
                                Icon(
                                    imageVector = if (uiState.selectedTab == AuraMediaType.VIDEO) Icons.Filled.Videocam else Icons.Outlined.Videocam,
                                    contentDescription = "تبويب مقاطع الفيديو",
                                    tint = if (uiState.selectedTab == AuraMediaType.VIDEO) PlatinumPrimary else SilverSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Tab 3: Files (Icon-Only)
                            IconButton(
                                onClick = { viewModel.selectTab(AuraMediaType.FILE) },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (uiState.selectedTab == AuraMediaType.FILE) MatteSurfaceElevated else MatteSurfaceCard
                                    )
                                    .border(
                                        1.dp,
                                        if (uiState.selectedTab == AuraMediaType.FILE) MatteBorder else MatteBorderSubtle,
                                        CircleShape
                                    )
                                    .testTag("tab_files_button")
                            ) {
                                Icon(
                                    imageVector = if (uiState.selectedTab == AuraMediaType.FILE) Icons.Filled.Description else Icons.Outlined.Description,
                                    contentDescription = "تبويب الملفات والمستندات",
                                    tint = if (uiState.selectedTab == AuraMediaType.FILE) PlatinumPrimary else SilverSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    val rawItems = when (uiState.selectedTab) {
                        AuraMediaType.IMAGE -> uiState.images
                        AuraMediaType.VIDEO -> uiState.videos
                        AuraMediaType.FILE -> uiState.files
                    }

                    val filteredItems = if (uiState.selectedFolder == "الكل") {
                        rawItems
                    } else {
                        rawItems.filter { it.bucketName == uiState.selectedFolder }
                    }

                    MediaGridView(
                        mediaType = uiState.selectedTab,
                        items = filteredItems,
                        dragController = dragController,
                        onQuickSend = { item ->
                            viewModel.sendItem(item)
                        }
                    )
                }
            }
        }

        // Manual Server Settings Dialog
        if (uiState.showManualServerDialog) {
            val currentHost = when (val state = discoveryState) {
                is com.example.data.network.DiscoveryState.Connected -> state.host
                is com.example.data.network.DiscoveryState.Discovered -> state.host
                else -> ""
            }
            val currentPort = when (val state = discoveryState) {
                is com.example.data.network.DiscoveryState.Connected -> state.port
                is com.example.data.network.DiscoveryState.Discovered -> state.port
                else -> 54321
            }

            ManualServerDialog(
                initialHost = currentHost.ifEmpty { uiState.currentHost },
                initialPort = if (currentPort > 0) currentPort else uiState.currentPort,
                initialToken = uiState.pairingToken,
                initialLegacy = uiState.useLegacyUpload,
                onDismiss = { viewModel.showManualServerDialog(false) },
                onConnect = { host, port, token, legacy ->
                    viewModel.connectManually(host, port, token, legacy)
                },
                onImportUri = { uri ->
                    viewModel.applyPairingUri(uri)
                }
            )
        }

        // Transfer History Bottom Sheet
        if (uiState.showHistorySheet) {
            TransferHistorySheet(
                transfers = activeTransfers,
                onDismiss = { viewModel.showHistorySheet(false) }
            )
        }
    }
}
