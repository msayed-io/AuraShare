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
    val selectedTab: AuraMediaType = AuraMediaType.IMAGE, val selectedFolder: String = "الكل",
    val images: List<MediaItem> = emptyList(), val videos: List<MediaItem> = emptyList(), val files: List<MediaItem> = emptyList(),
    val availableFolders: List<FolderOption> = emptyList(), val isMediaPermissionGranted: Boolean = false,
    val showHistorySheet: Boolean = false, val statusNotification: String? = null
)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val mediaRepository = MediaStoreRepository(application)
    val discoveryManager = MdnsDiscoveryManager(application, viewModelScope)
    val transferManager = StreamingTransferManager(application)
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    val discoveryState = discoveryManager.state
    val currentTransfer = transferManager.currentTransfer
    val activeTransfers = transferManager.activeTransfers
    init { checkPermissions() }
    fun checkPermissions() {
        val c = getApplication<Application>(); val granted = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        else ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        _uiState.update { it.copy(isMediaPermissionGranted = granted) }; discoveryManager.startDiscovery(); refreshMedia()
    }
    fun refreshMedia() = viewModelScope.launch { val imgs = mediaRepository.loadImages(); val vids = mediaRepository.loadVideos(); val fls = mediaRepository.loadFiles(); _uiState.update { it.copy(images=imgs,videos=vids,files=fls,availableFolders=extractFolderOptions(when(it.selectedTab){AuraMediaType.IMAGE->imgs;AuraMediaType.VIDEO->vids;AuraMediaType.FILE->fls})) } }
    fun selectTab(tab: AuraMediaType) { _uiState.update { val list=when(tab){AuraMediaType.IMAGE->it.images;AuraMediaType.VIDEO->it.videos;AuraMediaType.FILE->it.files};it.copy(selectedTab=tab,selectedFolder="الكل",availableFolders=extractFolderOptions(list)) } }
    fun selectFolder(folder: String) { _uiState.update { it.copy(selectedFolder=folder) } }
    fun pair(peer: NearbyPeer) = discoveryManager.pair(peer) { ok, error -> _uiState.update { it.copy(statusNotification = if (ok) "تم الاقتران بالحاسوب" else "تعذر الاقتران: ${error ?: "مرفوض"}") } }
    fun disconnect(peer: NearbyPeer) { discoveryManager.disconnect(peer) }
    fun sendItem(item: MediaItem) = sendItem(item, "top")
    fun sendItem(item: MediaItem, direction: String) {
        val connected = discoveryManager.nearbyPeers().filter { it.status == PeerStatus.CONNECTED }
        val peer = connected.singleOrNull(); val token = peer?.let { discoveryManager.tokenFor(it) }
        if (peer == null || token.isNullOrBlank()) {
            _uiState.update { it.copy(statusNotification = if (connected.size > 1)
                "يوجد أكثر من اتصال نشط؛ افصل الاتصالات ثم أعد المحاولة."
                else "لا يوجد اتصال نشط. اختر الكمبيوتر واضغط اقتران أولاً.") }
            return
        }
        viewModelScope.launch {
            transferManager.sendFile(item, peer.host, peer.port, token, useLegacyEndpoint = false, direction = direction) { success, error ->
                if (!success && error?.contains("401") == true) discoveryManager.disconnect(peer)
                _uiState.update { it.copy(statusNotification = if (success)
                    "تم إرسال ${item.name} بنجاح" else error ?: "فشل نقل الملف") }
            }
        }
    }
    fun refreshDiscovery() { discoveryManager.stopDiscovery(); discoveryManager.startDiscovery() }
    fun showHistorySheet(show: Boolean) { _uiState.update { it.copy(showHistorySheet=show) } }
    fun clearNotification() { _uiState.update { it.copy(statusNotification=null) } }
    private fun extractFolderOptions(items: List<MediaItem>): List<FolderOption> = listOf(FolderOption("الكل",items.size,Icons.Outlined.Folder)) + items.groupBy { it.bucketName }.map { (bucket,list) -> FolderOption(bucket,list.size,when { bucket.contains("camera",true)||bucket.contains("كاميرا") -> Icons.Outlined.CameraAlt; bucket.contains("download",true)||bucket.contains("تنزيل") -> Icons.Outlined.Download; bucket.contains("screen",true)||bucket.contains("لقطة") -> Icons.Outlined.Screenshot; else -> Icons.Outlined.Folder }) }
    override fun onCleared() { discoveryManager.stopDiscovery(); super.onCleared() }
}
