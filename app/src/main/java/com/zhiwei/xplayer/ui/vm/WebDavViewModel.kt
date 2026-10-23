package com.zhiwei.xplayer.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.core.webdav.DavAccount
import com.zhiwei.xplayer.core.webdav.DavEntry
import com.zhiwei.xplayer.core.webdav.WebDavClient
import com.zhiwei.xplayer.core.webdav.WebDavRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** WebDAV 浏览页的界面状态 */
data class DavBrowseState(
    val loading: Boolean = false,
    /** 当前所在路径，以 `/` 开头，`/` 表示账号根目录 */
    val path: String = "/",
    val entries: List<DavEntry> = emptyList(),
    val error: String? = null,
)

/**
 * WebDAV 浏览。
 *
 * 目录内容与账号列表都放在这里，界面只做展示。
 * 客户端的创建按「账号」缓存：同一个账号在同一个浏览会话里反复进出目录时，
 * 复用同一个 [WebDavClient] 才能命中它内部的列表缓存。
 */
@HiltViewModel
class WebDavViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WebDavRepository,
) : ViewModel() {

    val accounts: StateFlow<List<DavAccount>> = repository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(DavBrowseState())
    val state: StateFlow<DavBrowseState> = _state.asStateFlow()

    /** 当前选中的账号，null 表示还没选 */
    private val _account = MutableStateFlow<DavAccount?>(null)
    val account: StateFlow<DavAccount?> = _account.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var client: WebDavClient? = null
    private var loadJob: Job? = null
    private var loadGeneration = 0L

    fun addAccount(account: DavAccount) {
        viewModelScope.launch {
            try {
                repository.add(account)
                openAccount(account)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _messages.tryEmit(
                    context.getString(
                        R.string.webdav_save_failed,
                        error.message ?: context.getString(R.string.webdav_unknown_error),
                    ),
                )
            }
        }
    }

    fun removeAccount(account: DavAccount) {
        viewModelScope.launch {
            try {
                repository.remove(account)
                // 删掉的正好是当前账号，就退回未选状态
                if (_account.value == account) {
                    loadJob?.cancel()
                    _account.value = null
                    client = null
                    _state.value = DavBrowseState()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _messages.tryEmit(
                    context.getString(
                        R.string.webdav_remove_failed,
                        error.message ?: context.getString(R.string.webdav_unknown_error),
                    ),
                )
            }
        }
    }

    /** 选中账号并加载它的根目录。 */
    fun openAccount(account: DavAccount) {
        _account.value = account
        client = WebDavClient(account)
        load("/")
    }

    /** 进入子目录。 */
    fun openDirectory(entry: DavEntry) {
        if (!entry.isDirectory) return
        load(entry.path)
    }

    /** 返回上一级。已经在根目录就什么都不做。 */
    fun goUp() {
        val current = _state.value.path
        if (current == "/" || current.isBlank()) return
        val parent = current.trimEnd('/').substringBeforeLast('/', "")
        load(parent.ifEmpty { "/" })
    }

    /** 手动刷新：清掉客户端缓存再拉一次。 */
    fun refresh() {
        client?.invalidate()
        load(_state.value.path)
    }

    private fun load(path: String) {
        val active = client ?: return
        loadJob?.cancel()
        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val entries = active.list(path)
                if (generation != loadGeneration || client !== active) return@launch
                _state.value = DavBrowseState(loading = false, path = path, entries = entries)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation != loadGeneration || client !== active) return@launch
                _state.value = DavBrowseState(
                    loading = false,
                    path = path,
                    entries = emptyList(),
                    error = error.message ?: "加载失败",
                )
            }
        }
    }

    /** 给播放器用的直链（不含凭据）。 */
    fun playableUrl(entry: DavEntry): String? = client?.playableUrl(entry.path)

    /** 播放该条目需要附带的请求头（Basic 认证）。 */
    fun playbackHeaders(): List<String> = client?.playbackHeaders().orEmpty()
}
