package com.zhiwei.xplayer.core.data

import com.zhiwei.xplayer.core.data.db.FolderDao
import com.zhiwei.xplayer.core.data.db.FolderEntity
import com.zhiwei.xplayer.core.data.db.HistoryDao
import com.zhiwei.xplayer.core.data.db.HistoryEntity
import com.zhiwei.xplayer.core.data.db.PlaylistDao
import com.zhiwei.xplayer.core.data.db.PlaylistEntity
import com.zhiwei.xplayer.core.mpv.PlaybackSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放历史 / 授权目录 / 播放列表的统一入口。
 *
 * UI 层不直接碰 DAO —— 这样「历史最多留 N 条」之类的策略只需在一处实现。
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val historyDao: HistoryDao,
    private val folderDao: FolderDao,
    private val playlistDao: PlaylistDao,
) {

    val history: Flow<List<HistoryEntity>> = historyDao.observeAll()

    val recentHistory: Flow<List<HistoryEntity>> = historyDao.observeRecent(RECENT_LIMIT)

    val folders: Flow<List<FolderEntity>> = folderDao.observeAll()

    val playlist: Flow<List<PlaylistEntity>> = playlistDao.observeAll()

    suspend fun findHistory(uri: String): HistoryEntity? = historyDao.find(uri)

    /** 记录/更新一条播放进度。position 与 duration 都为 0 时只更新时间戳。 */
    suspend fun recordPlayback(
        uri: String,
        title: String,
        isNetwork: Boolean,
        positionMs: Long,
        durationMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        historyDao.upsert(
            HistoryEntity(
                uri = uri,
                title = title,
                isNetwork = isNetwork,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                updatedAt = nowMs,
            ),
        )
    }

    suspend fun removeHistory(uri: String) = historyDao.delete(uri)

    suspend fun clearHistory() = historyDao.clear()

    suspend fun addFolder(treeUri: String, displayName: String, nowMs: Long = System.currentTimeMillis()) {
        folderDao.upsert(FolderEntity(treeUri = treeUri, displayName = displayName, addedAt = nowMs))
    }

    suspend fun removeFolder(treeUri: String) = folderDao.delete(treeUri)

    /** 整体替换播放列表 */
    suspend fun replacePlaylist(sources: List<PlaybackSource>) {
        playlistDao.clear()
        if (sources.isEmpty()) return
        playlistDao.insertAll(
            sources.mapIndexed { index, source ->
                PlaylistEntity(
                    uri = source.uri,
                    title = source.title,
                    isNetwork = source.isNetwork,
                    position = index,
                )
            },
        )
    }

    suspend fun removeFromPlaylist(id: Long) = playlistDao.delete(id)

    suspend fun clearPlaylist() = playlistDao.clear()

    companion object {
        /** 「继续观看」只需要最近几条 */
        const val RECENT_LIMIT = 12
    }
}
