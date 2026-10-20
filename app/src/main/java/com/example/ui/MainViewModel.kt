package com.example.ui

import android.Manifest
import android.app.Application
import android.content.Context
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
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem
import com.example.data.model.PairingUriParser
import com.example.data.model.TransferRecord
import com.example.data.network.DiscoveryState
import com.example.data.network.MdnsDiscoveryManager
import com.example.data.network.StreamingTransferManager
import com.example.data.repository.MediaStoreRepository
import com.example.ui.components.FolderOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val selectedTab: AuraMediaType = AuraMediaType.IMAGE,
    val selectedFolder: String = "الكل",
    val images: List<MediaItem> = emptyList(),
    val videos: List<MediaItem> = emptyList(),
    val files: List<MediaItem> = emptyList(),
    val availableFolders: List<FolderOption> = emptyList(),
    val isMediaPermissionGranted: Boolean = false,
    val showManualServerDialog: Boolean = false,
    val showHistorySheet: Boolean = false,
    val statusNotification: String? = null,
    val currentHost: String = "",
    val currentPort: Int = 54321,
    val pairingToken: String? = null,
    val useLegacyUpload: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val mediaRepository = MediaStoreRepository(application)
    val discoveryManager = MdnsDiscoveryManager(application, viewModelScope)
    val transferManager = StreamingTransferManager(application)

    private val prefs = application.getSharedPreferences("aurashare_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        MainUiState(
            currentHost = prefs.getString("host", "") ?: "",
            currentPort = prefs.getInt("port", 54321),
            pairingToken = prefs.getString("token", null),
            useLegacyUpload = prefs.getBoolean("use_legacy", false)
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val discoveryState: StateFlow<DiscoveryState> = discoveryManager.state
    val currentTransfer: StateFlow<TransferRecord?> = transferManager.currentTransfer
    val activeTransfers: StateFlow<List<TransferRecord>> = transferManager.activeTransfers

    init {
        discoveryManager.activeToken = _uiState.value.pairingToken
        checkPermissions()
    }

    fun checkPermissions() {
        val context = getApplication<Application>()
        val hasMediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        _uiState.update {
            it.copy(isMediaPermissionGranted = hasMediaPermission)
        }

        discoveryManager.startDiscovery()
        refreshMedia()
    }

    fun refreshMedia() {
        viewModelScope.launch {
            val imgs = mediaRepository.loadImages()
            val vids = mediaRepository.loadVideos()
            val fls = mediaRepository.loadFiles()

            _uiState.update { state ->
                val currentItems = when (state.selectedTab) {
                    AuraMediaType.IMAGE -> imgs
                    AuraMediaType.VIDEO -> vids
                    AuraMediaType.FILE -> fls
                }

                val folders = extractFolderOptions(currentItems)

                state.copy(
                    images = imgs,
                    videos = vids,
                    files = fls,
                    availableFolders = folders
                )
            }
        }
    }

    fun selectTab(tab: AuraMediaType) {
        _uiState.update { state ->
            val itemsForTab = when (tab) {
                AuraMediaType.IMAGE -> state.images
                AuraMediaType.VIDEO -> state.videos
                AuraMediaType.FILE -> state.files
            }
            val folders = extractFolderOptions(itemsForTab)
            state.copy(
                selectedTab = tab,
                selectedFolder = "الكل",
                availableFolders = folders
            )
        }
    }

    fun selectFolder(folder: String) {
        _uiState.update { it.copy(selectedFolder = folder) }
    }

    fun sendItem(item: MediaItem) {
        val connectedState = discoveryState.value as? DiscoveryState.Connected
        if (connectedState == null) {
            // CRITICAL: Reject sending before verified connection. No fake fallback to loopback!
            _uiState.update {
                it.copy(statusNotification = "لا يوجد اتصال نشط ومتحقق منه بحاسوب AuraShare. يرجى الاقتران أولاً قبل الإرسال.")
            }
            return
        }

        val host = connectedState.host
        val port = connectedState.port
        val token = _uiState.value.pairingToken
        val useLegacy = _uiState.value.useLegacyUpload

        if (connectedState.requiresPairing && token.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    statusNotification = "الخادم يتطلب رمز اقتران (Token). يرجى إدخال رمز الاقتران عبر الإعدادات.",
                    showManualServerDialog = true
                )
            }
            return
        }

        viewModelScope.launch {
            transferManager.sendFile(
                item = item,
                host = host,
                port = port,
                token = token,
                useLegacyEndpoint = useLegacy
            ) { success, error ->
                if (success) {
                    _uiState.update { it.copy(statusNotification = "تم إرسال ${item.name} بنجاح إلى ${connectedState.deviceName}") }
                } else {
                    val is401 = error?.contains("401") == true
                    val message = if (is401) {
                        "رمز الاقتران غير صالح أو منتهي الصلاحية (HTTP 401). يرجى إعادة إدخال الرمز من شاشة سطح المكتب."
                    } else {
                        error ?: "فشل نقل الملف"
                    }
                    _uiState.update {
                        it.copy(
                            statusNotification = message,
                            showManualServerDialog = if (is401) true else it.showManualServerDialog
                        )
                    }
                }
            }
        }
    }

    fun refreshDiscovery() {
        discoveryManager.stopDiscovery()
        discoveryManager.startDiscovery()
    }

    fun connectManually(host: String, port: Int, token: String? = null, useLegacy: Boolean = false) {
        val cleanHost = host.trim()
        val cleanToken = token?.trim()?.ifEmpty { null }
        
        prefs.edit()
            .putString("host", cleanHost)
            .putInt("port", port)
            .putString("token", cleanToken)
            .putBoolean("use_legacy", useLegacy)
            .apply()

        _uiState.update {
            it.copy(
                currentHost = cleanHost,
                currentPort = port,
                pairingToken = cleanToken,
                useLegacyUpload = useLegacy
            )
        }

        discoveryManager.activeToken = cleanToken
        discoveryManager.connectManually(cleanHost, port, cleanToken)
    }

    fun applyPairingUri(uriString: String): Boolean {
        val config = PairingUriParser.parse(uriString) ?: return false
        connectManually(
            host = config.host,
            port = config.port,
            token = config.token,
            useLegacy = config.useLegacyUpload
        )
        _uiState.update {
            it.copy(statusNotification = "تم استيراد بيانات الاقتران بالحاسوب: ${config.host}:${config.port}")
        }
        return true
    }

    fun showManualServerDialog(show: Boolean) {
        _uiState.update { it.copy(showManualServerDialog = show) }
    }

    fun showHistorySheet(show: Boolean) {
        _uiState.update { it.copy(showHistorySheet = show) }
    }

    fun clearNotification() {
        _uiState.update { it.copy(statusNotification = null) }
    }

    private fun extractFolderOptions(items: List<MediaItem>): List<FolderOption> {
        val allOption = FolderOption("الكل", items.size, Icons.Outlined.Folder)
        val grouped = items.groupBy { it.bucketName }
        val rest = grouped.map { (bucket, list) ->
            val icon = when {
                bucket.contains("كاميرا", ignoreCase = true) || bucket.contains("camera", ignoreCase = true) -> Icons.Outlined.CameraAlt
                bucket.contains("تنزيل", ignoreCase = true) || bucket.contains("download", ignoreCase = true) -> Icons.Outlined.Download
                bucket.contains("لقطة", ignoreCase = true) || bucket.contains("screen", ignoreCase = true) -> Icons.Outlined.Screenshot
                else -> Icons.Outlined.Folder
            }
            FolderOption(bucket, list.size, icon)
        }
        return listOf(allOption) + rest
    }

    override fun onCleared() {
        super.onCleared()
        discoveryManager.stopDiscovery()
    }
}
