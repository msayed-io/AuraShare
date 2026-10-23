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
    val showHistorySheet: Boolean = false, val statusNotification: String? = null,
    val showQrScanner: Boolean = false, val pendingLegacyPeer: NearbyPeer? = null
)

/** Discovery is only a routing hint, never proof of the peer's identity. */
internal fun legacyCandidateAllowed(peer: NearbyPeer, devices: List<TrustedDevice>): Boolean =
    devices.none { it.id == peer.secureServerId || it.host == peer.host }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val mediaRepository = MediaStoreRepository(application)
    val discoveryManager = MdnsDiscoveryManager(application, viewModelScope)
    val transferManager = StreamingTransferManager(application)
    private val secureManager = SecurePairingManager(application)
    val secureState = secureManager.state
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    val discoveryState: StateFlow<DiscoveryState> = combine(discoveryManager.state, secureState) { discovery, secure ->
        if (discovery is DiscoveryState.Nearby) discovery.copy(peers = discovery.peers.filter { secure.trustAvailable && legacyCandidateAllowed(it, secure.devices) })
        else discovery
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DiscoveryState.Idle)
    val currentTransfer = transferManager.currentTransfer
    val activeTransfers = transferManager.activeTransfers
    private var legacyConsentEndpoint: String? = null
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

    // No legacy pairing request (and no phone name/client secret) is sent before this explicit consent.
    fun pair(peer: NearbyPeer) {
        if (!secureState.value.trustAvailable || secureState.value.busy || !legacyCandidateAllowed(peer, secureState.value.devices)) {
            notify("هذا الجهاز محفوظ بأمان؛ استخدم اتصال الجهاز الموثوق. لا يُسمح بالرجوع إلى HTTP.")
            return
        }
        _uiState.update { it.copy(pendingLegacyPeer = peer) }
    }
    fun cancelLegacyPairing() { _uiState.update { it.copy(pendingLegacyPeer = null) } }
    fun confirmLegacyPairing() {
        val peer = _uiState.value.pendingLegacyPeer ?: return
        cancelLegacyPairing()
        if (!secureState.value.trustAvailable || !legacyCandidateAllowed(peer, secureState.value.devices) || secureState.value.busy) return
        legacyConsentEndpoint = "${peer.host}:${peer.port}"
        viewModelScope.launch {
            secureManager.disconnect()
            if (legacyConsentEndpoint != "${peer.host}:${peer.port}" || !secureState.value.trustAvailable ||
                !legacyCandidateAllowed(peer, secureState.value.devices)) return@launch
            discoveryManager.pair(peer) { ok, error -> notify(if (ok) "تم الاتصال القديم — النقل غير مشفّر" else "تعذر الاقتران: ${error ?: "مرفوض"}") }
        }
    }
    fun disconnect(peer: NearbyPeer) { legacyConsentEndpoint = null; discoveryManager.disconnect(peer) }
    fun showQrScanner(show: Boolean) { _uiState.update { it.copy(showQrScanner = show) } }
    fun pairSecureQr(raw: String) {
        showQrScanner(false)
        if (secureState.value.busy) return
        legacyConsentEndpoint = null
        discoveryManager.nearbyPeers().filter { it.status == PeerStatus.CONNECTED || it.status == PeerStatus.PAIRING }.forEach { discoveryManager.disconnect(it) }
        viewModelScope.launch { secureManager.pairQr(raw) }
    }
    fun connectTrusted(id: String) {
        if (secureState.value.busy) return
        legacyConsentEndpoint = null
        discoveryManager.nearbyPeers().filter { it.status == PeerStatus.CONNECTED || it.status == PeerStatus.PAIRING }.forEach { discoveryManager.disconnect(it) }
        val hint = discoveryManager.nearbyPeers().firstOrNull { it.secureServerId == id }
        viewModelScope.launch { secureManager.connect(id, hint?.host, hint?.securePort) }
    }
    fun disconnectTrusted() { viewModelScope.launch { secureManager.disconnect() } }
    fun forgetTrusted(id: String) { viewModelScope.launch { secureManager.forget(id) } }

    fun sendItem(item: MediaItem) = sendItem(item, "top")
    fun sendItem(item: MediaItem, direction: String) {
        if (!secureState.value.trustAvailable || secureState.value.busy) {
            notify("انتظر اكتمال التحقق من الاتصال الآمن؛ لم يُرسل الملف.")
            return
        }
        val secure = secureState.value.connected
        if (secure != null) {
            viewModelScope.launch {
                transferManager.sendFile(item, secure.device.host, secure.device.port, secure.token, direction = direction,
                    secureClient = secure.client, secureBaseUrl = secure.baseUrl) { success, error ->
                    if (!success && error?.contains("401") == true) disconnectTrusted()
                    notify(if (success) "تم إرسال ${item.name} بأمان" else error ?: "فشل نقل الملف الآمن")
                }
            }
            return
        }
        val connected = discoveryManager.nearbyPeers().filter { it.status == PeerStatus.CONNECTED }
        val peer = connected.singleOrNull(); val token = peer?.let { discoveryManager.tokenFor(it) }
        if (peer == null || token.isNullOrBlank()) {
            notify(if (connected.size > 1) "يوجد أكثر من اتصال نشط؛ افصل الاتصالات ثم أعد المحاولة." else "لا يوجد اتصال نشط. اتصل بجهاز موثوق أو امسح QR للاقتران الآمن.")
            return
        }
        if (legacyConsentEndpoint != "${peer.host}:${peer.port}" || !legacyCandidateAllowed(peer, secureState.value.devices)) {
            discoveryManager.disconnect(peer)
            notify("تم منع اتصال قديم بجهاز موثوق؛ أعد الاتصال الآمن.")
            return
        }
        viewModelScope.launch {
            transferManager.sendFile(item, peer.host, peer.port, token, useLegacyEndpoint = false, direction = direction) { success, error ->
                if (!success && error?.contains("401") == true) discoveryManager.disconnect(peer)
                notify(if (success) "تم إرسال ${item.name} — عبر اتصال قديم غير مشفّر" else error ?: "فشل نقل الملف")
            }
        }
    }
    fun refreshDiscovery() { legacyConsentEndpoint = null; discoveryManager.stopDiscovery(); discoveryManager.startDiscovery() }
    fun showHistorySheet(show: Boolean) { _uiState.update { it.copy(showHistorySheet=show) } }
    fun clearNotification() { _uiState.update { it.copy(statusNotification=null) } }
    private fun notify(message: String) { _uiState.update { it.copy(statusNotification=message) } }
    private fun extractFolderOptions(items: List<MediaItem>): List<FolderOption> = listOf(FolderOption("الكل",items.size,Icons.Outlined.Folder)) + items.groupBy { it.bucketName }.map { (bucket,list) -> FolderOption(bucket,list.size,when { bucket.contains("camera",true)||bucket.contains("كاميرا") -> Icons.Outlined.CameraAlt; bucket.contains("download",true)||bucket.contains("تنزيل") -> Icons.Outlined.Download; bucket.contains("screen",true)||bucket.contains("لقطة") -> Icons.Outlined.Screenshot; else -> Icons.Outlined.Folder }) }
    override fun onCleared() { secureManager.close(); discoveryManager.stopDiscovery(); super.onCleared() }
}
