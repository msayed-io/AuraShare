package com.example.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Screenshot
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.*
import com.example.data.network.*
import com.example.data.repository.MediaStoreRepository
import com.example.ui.components.FolderOption
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MainUiState(
    val selectedTab: AuraMediaType = AuraMediaType.IMAGE,
    val selectedFolder: String = "الكل",
    val images: List<MediaItem> = emptyList(),
    val videos: List<MediaItem> = emptyList(),
    val files: List<MediaItem> = emptyList(),
    val availableFolders: List<FolderOption> = emptyList(),
    val isMediaPermissionGranted: Boolean = false,
    val showHistorySheet: Boolean = false,
    val statusNotification: String? = null,
    val showQrScanner: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val mediaRepository = MediaStoreRepository(application)
    private val secureManager = SecurePairingManager(application)
    private val routeDiscovery = TrustedDeviceRouteDiscoveryManager(application, viewModelScope)
    val transferManager = StreamingTransferManager(application)
    val secureState = secureManager.state
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    val currentTransfer = transferManager.currentTransfer
    val activeTransfers = transferManager.activeTransfers

    init {
        viewModelScope.launch {
            secureState
                .map { state -> if (state.trustAvailable) state.devices.mapTo(linkedSetOf()) { it.id } else emptySet() }
                .distinctUntilChanged()
                .collect(routeDiscovery::setTrustedDeviceIds)
        }
        checkPermissions()
    }

    fun checkPermissions() {
        val context = getApplication<Application>()
        val granted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        _uiState.update { it.copy(isMediaPermissionGranted = granted) }
        refreshMedia()
    }

    fun refreshMedia() = viewModelScope.launch {
        val images = mediaRepository.loadImages()
        val videos = mediaRepository.loadVideos()
        val files = mediaRepository.loadFiles()
        _uiState.update {
            it.copy(
                images = images,
                videos = videos,
                files = files,
                availableFolders = extractFolderOptions(
                    when (it.selectedTab) {
                        AuraMediaType.IMAGE -> images
                        AuraMediaType.VIDEO -> videos
                        AuraMediaType.FILE -> files
                    },
                ),
            )
        }
    }

    fun selectTab(tab: AuraMediaType) {
        _uiState.update { state ->
            val list = when (tab) {
                AuraMediaType.IMAGE -> state.images
                AuraMediaType.VIDEO -> state.videos
                AuraMediaType.FILE -> state.files
            }
            state.copy(selectedTab = tab, selectedFolder = "الكل", availableFolders = extractFolderOptions(list))
        }
    }

    fun selectFolder(folder: String) { _uiState.update { it.copy(selectedFolder = folder) } }

    fun showQrScanner(show: Boolean) { _uiState.update { it.copy(showQrScanner = show) } }

    fun pairSecureQr(raw: String) {
        showQrScanner(false)
        if (secureState.value.busy) return
        viewModelScope.launch { secureManager.pairQr(raw) }
    }

    fun connectTrusted(id: String) {
        if (secureState.value.busy) return
        val hint = routeDiscovery.hintFor(id)
        viewModelScope.launch { secureManager.connect(id, hint?.host, hint?.port) }
    }

    fun disconnectTrusted() { viewModelScope.launch { secureManager.disconnect() } }
    fun forgetTrusted(id: String) { viewModelScope.launch { secureManager.forget(id) } }

    fun sendItem(item: MediaItem) = sendItem(item, "top")

    fun sendItem(item: MediaItem, direction: String) {
        val state = secureState.value
        if (!state.trustAvailable || state.busy) {
            notify("انتظر اكتمال التحقق من الاتصال الآمن؛ لم يُرسل الملف.")
            return
        }
        val connection = state.connected
        if (connection == null) {
            notify("لا يوجد جهاز مرتبط متصل. اتصل بجهاز محفوظ أو امسح QR للربط الآمن.")
            return
        }
        viewModelScope.launch {
            transferManager.sendFile(item, connection, direction = direction) { success, error ->
                if (!success && error?.contains("401") == true) disconnectTrusted()
                notify(if (success) "تم إرسال ${item.name} بأمان" else error ?: "فشل نقل الملف الآمن")
            }
        }
    }

    fun showHistorySheet(show: Boolean) { _uiState.update { it.copy(showHistorySheet = show) } }
    fun clearNotification() { _uiState.update { it.copy(statusNotification = null) } }
    private fun notify(message: String) { _uiState.update { it.copy(statusNotification = message) } }

    private fun extractFolderOptions(items: List<MediaItem>): List<FolderOption> =
        listOf(FolderOption("الكل", items.size, Icons.Outlined.Folder)) +
            items.groupBy { it.bucketName }.map { (bucket, list) ->
                FolderOption(
                    bucket,
                    list.size,
                    when {
                        bucket.contains("camera", true) || bucket.contains("كاميرا") -> Icons.Outlined.CameraAlt
                        bucket.contains("download", true) || bucket.contains("تنزيل") -> Icons.Outlined.Download
                        bucket.contains("screen", true) || bucket.contains("لقطة") -> Icons.Outlined.Screenshot
                        else -> Icons.Outlined.Folder
                    },
                )
            }

    override fun onCleared() {
        secureManager.close()
        routeDiscovery.stopDiscovery()
        super.onCleared()
    }
}
