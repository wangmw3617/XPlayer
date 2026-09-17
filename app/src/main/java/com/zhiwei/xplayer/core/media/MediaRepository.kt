package com.zhiwei.xplayer.core.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.zhiwei.xplayer.core.util.Formatters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaStore 媒体扫描。
 *
 * 只读 `EXTERNAL_CONTENT_URI`（外置共享存储的汇总视图），不做多卷遍历 ——
 * 多卷枚举在 Android 10+ 上需要 `MediaStore.getExternalVolumeNames`，
 * 而汇总 URI 已经把各卷合并了，对本应用来说足够。
 */
@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 当前是否有读取媒体的权限（分版本判断） */
    fun hasMediaPermission(): Boolean {
        val permission = requiredReadPermission() ?: return true
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun requiredReadPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun requiredReadPermission(): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_VIDEO
        else -> Manifest.permission.READ_EXTERNAL_STORAGE
    }

    suspend fun loadAll(): List<MediaEntry> = withContext(Dispatchers.IO) {
        if (!hasMediaPermission()) return@withContext emptyList()
        val result = ArrayList<MediaEntry>()
        runCatching { result.addAll(queryVideos()) }
        runCatching { result.addAll(queryAudio()) }
        result
    }

    suspend fun loadVideos(): List<MediaEntry> = withContext(Dispatchers.IO) {
        if (!hasMediaPermission()) return@withContext emptyList()
        runCatching { queryVideos() }.getOrDefault(emptyList())
    }

    suspend fun loadAudio(): List<MediaEntry> = withContext(Dispatchers.IO) {
        if (!hasMediaPermission()) return@withContext emptyList()
        runCatching { queryAudio() }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- video ----

    private fun queryVideos(): List<MediaEntry> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATA,
        )
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val out = ArrayList<MediaEntry>()
        context.contentResolver.query(collection, projection, null, null, sort)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.Video.Media._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            val titleIdx = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
            val durationIdx = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            val sizeIdx = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
            val addedIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
            val bucketIdx = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val widthIdx = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightIdx = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            val dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

            while (cursor.moveToNext()) {
                val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                val path = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                val fileName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val title = if (titleIdx >= 0) cursor.getString(titleIdx) else null
                out.add(
                    MediaEntry(
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        title = pickTitle(title, fileName, path),
                        fileName = pickFileName(fileName, path),
                        durationMs = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L,
                        sizeBytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L,
                        addedAt = if (addedIdx >= 0) cursor.getLong(addedIdx) * 1000L else 0L,
                        isVideo = true,
                        bucket = if (bucketIdx >= 0) cursor.getString(bucketIdx) else null,
                        width = if (widthIdx >= 0) cursor.getInt(widthIdx) else 0,
                        height = if (heightIdx >= 0) cursor.getInt(heightIdx) else 0,
                        path = path,
                    ),
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------- audio ----

    private fun queryAudio(): List<MediaEntry> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        val out = ArrayList<MediaEntry>()
        context.contentResolver.query(collection, projection, selection, null, sort)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val durationIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val sizeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val addedIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val albumIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                val path = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                val fileName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val title = if (titleIdx >= 0) cursor.getString(titleIdx) else null
                out.add(
                    MediaEntry(
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        title = pickTitle(title, fileName, path),
                        fileName = pickFileName(fileName, path),
                        durationMs = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L,
                        sizeBytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L,
                        addedAt = if (addedIdx >= 0) cursor.getLong(addedIdx) * 1000L else 0L,
                        isVideo = false,
                        bucket = if (albumIdx >= 0) cursor.getString(albumIdx) else null,
                        width = 0,
                        height = 0,
                        path = path,
                    ),
                )
            }
        }
        return out
    }

    // --------------------------------------------------------------- helpers ----

    private fun pickTitle(title: String?, fileName: String?, path: String?): String {
        title?.takeIf { it.isNotBlank() }?.let { return it }
        return pickFileName(fileName, path)
    }

    private fun pickFileName(fileName: String?, path: String?): String {
        fileName?.takeIf { it.isNotBlank() }?.let { return it }
        path?.let { File(it).name.takeIf { name -> name.isNotBlank() } }?.let { return it }
        return "未知文件"
    }

    companion object {
        /** 从 uri 里取出 MediaStore 的 id（用于缩略图 API），失败返回 -1 */
        fun idOf(uri: Uri): Long = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)

        /** 从 uri 里取出 MediaStore 的 id（用于缩略图 API），失败返回 -1 */
        fun idOf(uri: String): Long = idOf(Uri.parse(uri))

        /** 供 UI 直接用的扩展名判断 */
        fun looksLikeVideo(fileName: String): Boolean = Formatters.isVideoFile(fileName)
    }
}
