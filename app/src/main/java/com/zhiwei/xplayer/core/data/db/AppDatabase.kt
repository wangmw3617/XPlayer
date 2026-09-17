package com.zhiwei.xplayer.core.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 播放历史。
 *
 * 用 uri 做主键：同一个媒体反复播放只留一条，位置就地更新，
 * 「继续观看」直接取 updatedAt 最新的那条即可，不需要额外去重逻辑。
 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val isNetwork: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/** 用户通过 SAF 授权过的目录 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val treeUri: String,
    val displayName: String,
    val addedAt: Long,
)

/** 简单播放列表（手动添加的播放队列） */
@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uri: String,
    val title: String,
    val isNetwork: Boolean,
    val position: Int,
)

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE uri = :uri LIMIT 1")
    suspend fun find(uri: String): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FolderEntity)

    @Query("DELETE FROM folders WHERE treeUri = :treeUri")
    suspend fun delete(treeUri: String)
}

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlist ORDER BY position ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PlaylistEntity>)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist")
    suspend fun clear()
}

/**
 * 数据库。
 *
 * `exportSchema = false`：本项目没有跨版本迁移的历史包袱，1.0.0 是首个版本；
 * 一旦开始发版就应该打开并提交 schema，避免后续改表时无法验证迁移。
 */
@Database(
    entities = [HistoryEntity::class, FolderEntity::class, PlaylistEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class XPlayerDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun folderDao(): FolderDao
    abstract fun playlistDao(): PlaylistDao
}
