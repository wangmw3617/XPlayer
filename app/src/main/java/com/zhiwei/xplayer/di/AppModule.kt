package com.zhiwei.xplayer.di

import android.content.Context
import androidx.room.Room
import com.zhiwei.xplayer.core.data.db.FolderDao
import com.zhiwei.xplayer.core.data.db.HistoryDao
import com.zhiwei.xplayer.core.data.db.PlaylistDao
import com.zhiwei.xplayer.core.data.db.XPlayerDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 单例作用域的依赖。
 *
 * 只提供 Room 这一条链：`SettingsRepository` / `LibraryRepository` / `MpvPlayer`
 * 都有 `@Inject constructor`，Hilt 能自己构造，不必再写一遍 `@Provides`。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): XPlayerDatabase =
        Room.databaseBuilder(context, XPlayerDatabase::class.java, "xplayer.db")
            // 1.0.0 是首个版本，还没有需要保留的历史数据；后续加表时改成真正的 Migration
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideHistoryDao(db: XPlayerDatabase): HistoryDao = db.historyDao()

    @Provides
    fun provideFolderDao(db: XPlayerDatabase): FolderDao = db.folderDao()

    @Provides
    fun providePlaylistDao(db: XPlayerDatabase): PlaylistDao = db.playlistDao()
}
