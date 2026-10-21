package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AuraMediaType
import com.example.ui.components.AppleFolderDropdown
import com.example.ui.components.DesktopCapsuleHeader
import com.example.ui.components.FloatingBackdropHost
import com.example.ui.components.FloatingBottomNavigation
import com.example.ui.components.FloatingTopDissolve
import com.example.ui.components.FluidMetaballContainer
import com.example.ui.components.MediaGridView
import com.example.ui.components.PermissionGate
import com.example.ui.components.TransferHistorySheet
import com.example.ui.components.rememberFluidDragController

@Composable
fun AuraShareScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val discoveryState by viewModel.discoveryState.collectAsState()
    val currentTransfer by viewModel.currentTransfer.collectAsState()
    val activeTransfers by viewModel.activeTransfers.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val dragController = rememberFluidDragController(onSendItem = viewModel::sendItem)
    val density = LocalDensity.current
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }

    LaunchedEffect(uiState.statusNotification) {
        uiState.statusNotification?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearNotification()
        }
    }

    PermissionGate(
        isMediaPermissionGranted = uiState.isMediaPermissionGranted,
        onPermissionsUpdated = viewModel::checkPermissions
    ) {
        FluidMetaballContainer(dragController = dragController) {
            FloatingBackdropHost(
                modifier = Modifier.fillMaxSize().blur(if (uiState.showHistorySheet) 4.dp else 0.dp),
                content = {
                    val rawItems = when (uiState.selectedTab) {
                        AuraMediaType.IMAGE -> uiState.images
                        AuraMediaType.VIDEO -> uiState.videos
                        AuraMediaType.FILE -> uiState.files
                    }
                    val filteredItems = if (uiState.selectedFolder == "الكل") rawItems
                        else rawItems.filter { it.bucketName == uiState.selectedFolder }
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        MediaGridView(
                            mediaType = uiState.selectedTab,
                            items = filteredItems,
                            dragController = dragController,
                            onQuickSend = viewModel::sendItem,
                            // Insets belong to the scrolling content, not a fixed parent:
                            // every card can travel behind both independent floating controls.
                            contentPadding = PaddingValues(
                                start = 12.dp, end = 12.dp,
                                top = 80.dp, bottom = bottomInset + 96.dp
                            ),
                            header = {
                                Row(
                                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = when (uiState.selectedTab) {
                                            AuraMediaType.IMAGE -> "معرض الصور"
                                            AuraMediaType.VIDEO -> "مقاطع الفيديو"
                                            AuraMediaType.FILE -> "المستندات والملفات"
                                        },
                                        color = MaterialTheme.colorScheme.onBackground,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    AppleFolderDropdown(
                                        folders = uiState.availableFolders,
                                        selectedFolder = uiState.selectedFolder,
                                        onFolderSelect = viewModel::selectFolder
                                    )
                                }
                            }
                        )
                    }
                },
                overlay = {
                    // Drawing-only layers have no pointer handlers or click semantics.
                    FloatingTopDissolve(Modifier.align(Alignment.TopCenter))
                    DesktopCapsuleHeader(
                        discoveryState = discoveryState,
                        currentTransfer = currentTransfer,
                        onRefreshDiscovery = viewModel::refreshDiscovery,
                        onPair = viewModel::pair,
                        onDisconnect = viewModel::disconnect,
                        onOpenHistory = { viewModel.showHistorySheet(true) },
                        onCapsuleBoundsChanged = {},
                        isBeingAttracted = dragController.isAttracted,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                    // AuraShare has no create/add action: use a single centered capsule.
                    FloatingBottomNavigation(
                        selectedTab = when (uiState.selectedTab) {
                            AuraMediaType.IMAGE -> "images"
                            AuraMediaType.VIDEO -> "videos"
                            AuraMediaType.FILE -> "files"
                        },
                        onSelectTab = { key ->
                            viewModel.selectTab(when (key) {
                                "videos" -> AuraMediaType.VIDEO
                                "files" -> AuraMediaType.FILE
                                else -> AuraMediaType.IMAGE
                            })
                        },
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .navigationBarsPadding().padding(bottom = 16.dp)
                    )
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .navigationBarsPadding().padding(bottom = 80.dp)
                    )
                }
            )
        }
        if (uiState.showHistorySheet) {
            TransferHistorySheet(
                transfers = activeTransfers,
                onDismiss = { viewModel.showHistorySheet(false) }
            )
        }
    }
}
