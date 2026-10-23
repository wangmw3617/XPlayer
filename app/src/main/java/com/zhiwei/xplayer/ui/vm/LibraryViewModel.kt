package com.zhiwei.xplayer.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwei.xplayer.core.data.LibraryRepository
import com.zhiwei.xplayer.core.data.db.FolderEntity
import com.zhiwei.xplayer.core.data.db.HistoryEntity
import com.zhiwei.xplayer.core.data.db.PlaylistEntity
import com.zhiwei.xplayer.core.media.MediaCatalogState
import com.zhiwei.xplayer.core.media.MediaRepository
import com.zhiwei.xplayer.core.media.SafBrowser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    val catalog: StateFlow<MediaCatalogState> = mediaRepository.catalog

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

    /** 已授权音频、只需补视频权限时使用。 */
    fun requiredVideoPermissions(): Array<String> = mediaRepository.requiredVideoPermissions()

    /** 首次进入时复用应用级扫描结果，不会因为首页和媒体库各有一个 VM 就扫两遍。 */
    fun ensureLoaded() {
        viewModelScope.launch { mediaRepository.refresh(force = false) }
    }

    /** 权限变化后强制重新读取，保证视频和音频权限分别生效。 */
    fun refresh() {
        viewModelScope.launch { mediaRepository.refresh(force = true) }
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
