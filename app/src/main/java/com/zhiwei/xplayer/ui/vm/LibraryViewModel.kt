package com.zhiwei.xplayer.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwei.xplayer.core.data.LibraryRepository
import com.zhiwei.xplayer.core.data.db.FolderEntity
import com.zhiwei.xplayer.core.data.db.HistoryEntity
import com.zhiwei.xplayer.core.data.db.PlaylistEntity
import com.zhiwei.xplayer.core.media.MediaEntry
import com.zhiwei.xplayer.core.media.MediaRepository
import com.zhiwei.xplayer.core.media.SafBrowser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 媒体库 / 首页 / 文件夹 / 历史 共用的 ViewModel。
 *
 * 这几屏读的是同一份数据（媒体扫描结果 + Room 里的历史与授权目录），
 * 拆成多个 ViewModel 只会让「扫描完刷新首页」这种事需要跨 VM 通信。
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _videos = MutableStateFlow<List<MediaEntry>>(emptyList())
    val videos: StateFlow<List<MediaEntry>> = _videos.asStateFlow()

    private val _audio = MutableStateFlow<List<MediaEntry>>(emptyList())
    val audio: StateFlow<List<MediaEntry>> = _audio.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _scanned = MutableStateFlow(false)
    val scanned: StateFlow<Boolean> = _scanned.asStateFlow()

    private val _permissionGranted = MutableStateFlow(mediaRepository.hasMediaPermission())
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    val history: StateFlow<List<HistoryEntity>> = libraryRepository.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentHistory: StateFlow<List<HistoryEntity>> = libraryRepository.recentHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<FolderEntity>> = libraryRepository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlist: StateFlow<List<PlaylistEntity>> = libraryRepository.playlist
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 需要申请哪些权限（分版本） */
    fun requiredPermissions(): Array<String> = mediaRepository.requiredReadPermissions()

    /** 权限回调后调用：重新判断并触发一次扫描 */
    fun refresh() {
        _permissionGranted.value = mediaRepository.hasMediaPermission()
        if (!_permissionGranted.value) {
            _videos.value = emptyList()
            _audio.value = emptyList()
            _scanned.value = true
            return
        }
        viewModelScope.launch {
            _loading.value = true
            val videos = mediaRepository.loadVideos()
            val audio = mediaRepository.loadAudio()
            _videos.value = videos
            _audio.value = audio
            _loading.value = false
            _scanned.value = true
        }
    }

    fun clearHistory() = viewModelScope.launch { libraryRepository.clearHistory() }

    fun removeHistory(uri: String) = viewModelScope.launch { libraryRepository.removeHistory(uri) }

    fun addFolder(treeUri: String) = viewModelScope.launch {
        libraryRepository.addFolder(treeUri, SafBrowser.displayNameOf(treeUri))
    }

    fun removeFolder(treeUri: String) = viewModelScope.launch {
        libraryRepository.removeFolder(treeUri)
    }

    fun clearPlaylist() = viewModelScope.launch { libraryRepository.clearPlaylist() }

    fun removeFromPlaylist(id: Long) = viewModelScope.launch {
        libraryRepository.removeFromPlaylist(id)
    }

    suspend fun historyOf(uri: String): HistoryEntity? = libraryRepository.findHistory(uri)
}
