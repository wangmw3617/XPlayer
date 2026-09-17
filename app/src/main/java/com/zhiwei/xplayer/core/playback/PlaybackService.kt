package com.zhiwei.xplayer.core.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zhiwei.xplayer.MainActivity
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.core.mpv.MpvPlayer
import com.zhiwei.xplayer.core.mpv.PlayerState
import com.zhiwei.xplayer.core.util.Formatters
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 播放前台服务。
 *
 * 存在的唯一理由是「进程别被杀」：libmpv 的播放线程活在应用进程里，一旦进程被
 * 系统回收音频就断了。挂在 mediaPlayback 类型的前台服务上，系统会把它当正在
 * 放音乐的进程对待，锁屏、切后台、息屏都能继续。
 *
 * 顺带承载锁屏与耳机的媒体按键：`MediaSessionCompat` 提供会话，
 * `MediaStyle` 通知把按钮显示在锁屏上。
 *
 * 服务本身不持有任何播放状态 —— 状态全在 [MpvPlayer] 这个应用级单例里，
 * 服务只是它的一个「外部控制器 + 通知展示层」。
 */
@AndroidEntryPoint
class PlaybackService : Service() {

    @Inject
    lateinit var player: MpvPlayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var session: MediaSessionCompat? = null

    /** 是否已经进入前台（前台服务必须先 startForeground 才能做事） */
    private var foreground = false

    /** 是否真的加载过媒体。避免服务刚起来、还没 loadfile 时就被「空闲」判定自杀 */
    private var sawMedia = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, SESSION_TAG).apply {
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() = player.setPaused(false)
                    override fun onPause() = player.setPaused(true)
                    override fun onStop() = stopPlayback()
                    override fun onSkipToNext() = player.next()
                    override fun onSkipToPrevious() = player.previous()
                    override fun onSeekTo(pos: Long) = player.seekTo(pos)
                },
            )
            isActive = true
        }

        scope.launch {
            player.state.collectLatest { state ->
                if (!state.idle && state.source != null) sawMedia = true
                if (sawMedia && state.idle) {
                    // 播放已结束且没有新内容：没必要继续占着前台服务
                    stopPlayback()
                    return@collectLatest
                }
                updateSession(state)
                pushNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 无论因为什么被拉起，都要先满足「5 秒内进入前台」的硬性要求
        if (!foreground) {
            startForegroundCompat(buildNotification(player.state.value))
            foreground = true
        }
        when (intent?.action) {
            ACTION_TOGGLE -> player.togglePause()
            ACTION_PREVIOUS -> player.previous()
            ACTION_NEXT -> player.next()
            ACTION_STOP -> stopPlayback()
            else -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        session?.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ============================================================== 通知 ====

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: PlayerState): Notification {
        val title = state.mediaTitle.ifBlank { state.source?.title ?: getString(R.string.app_name) }
        val subtitle = buildSubtitle(state)

        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentIntent)
            .setOngoing(!state.paused)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.notif_action_prev),
                serviceIntent(ACTION_PREVIOUS, REQUEST_PREVIOUS),
            )
            .addAction(
                R.drawable.ic_notification,
                getString(if (state.paused) R.string.notif_action_play else R.string.notif_action_pause),
                serviceIntent(ACTION_TOGGLE, REQUEST_TOGGLE),
            )
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.notif_action_next),
                serviceIntent(ACTION_NEXT, REQUEST_NEXT),
            )
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.notif_action_stop),
                serviceIntent(ACTION_STOP, REQUEST_STOP),
            )

        session?.let { active ->
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(active.sessionToken)
                    // 折叠态只放「上一首 / 播放暂停 / 下一首」
                    .setShowActionsInCompactView(0, 1, 2),
            )
        }
        return builder.build()
    }

    private fun buildSubtitle(state: PlayerState): String {
        if (state.durationMs <= 0L) return getString(R.string.app_slogan)
        return "${Formatters.position(state.positionMs)} / ${Formatters.duration(state.durationMs)}"
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun pushNotification(state: PlayerState) {
        if (!foreground) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateSession(state: PlayerState) {
        val active = session ?: return
        val playbackState = when {
            state.buffering -> PlaybackStateCompat.STATE_BUFFERING
            state.paused || state.idle -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_PLAYING
        }
        active.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO,
                )
                .setState(playbackState, state.positionMs, state.speed)
                .build(),
        )
        active.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(
                    MediaMetadataCompat.METADATA_KEY_TITLE,
                    state.mediaTitle.ifBlank { state.source?.title.orEmpty() },
                )
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
                .build(),
        )
    }

    private fun stopPlayback() {
        player.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foreground = false
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "xplayer_playback"
        private const val NOTIFICATION_ID = 1001
        private const val SESSION_TAG = "XPlayer"

        private const val REQUEST_CONTENT = 10
        private const val REQUEST_PREVIOUS = 11
        private const val REQUEST_TOGGLE = 12
        private const val REQUEST_NEXT = 13
        private const val REQUEST_STOP = 14

        const val ACTION_TOGGLE = "com.zhiwei.xplayer.action.TOGGLE"
        const val ACTION_PREVIOUS = "com.zhiwei.xplayer.action.PREVIOUS"
        const val ACTION_NEXT = "com.zhiwei.xplayer.action.NEXT"
        const val ACTION_STOP = "com.zhiwei.xplayer.action.STOP"

        /** 播放开始且允许后台播放时调用 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PlaybackService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
