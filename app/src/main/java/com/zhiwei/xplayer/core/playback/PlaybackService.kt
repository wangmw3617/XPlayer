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
import android.os.SystemClock
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
            // 通知与会话**不能**跟着每一次 state 变化走。
            //
            // player.state 的发射频率跟 time-pos 一样高（每帧一次，最高 60Hz），
            // 而推一次通知的实际开销是：
            //   buildNotification 里 4 次 PendingIntent.getService（每次都是到系统
            //   ActivityManager 的 binder 往返）+ 1 次 NotificationManager.notify，
            //   再加上 setPlaybackState / setMetadata 两次 MediaSession 的 IPC。
            // 全都在 Dispatchers.Main 上 —— 合起来每秒几百次主线程 IPC，
            // 界面必卡，而且和当前在哪个页面无关。
            //
            // 现在分两类：
            //   · 离散字段（暂停/缓冲/跳转中/标题/时长）一变 → 立刻推；
            //   · 只有进度在走 → 最多每 POSITION_PUSH_INTERVAL_MS 推一次，
            //     锁屏进度条与通知上的秒数不需要比这更细。
            var lastKey: SessionKey? = null
            var lastSecond = Long.MIN_VALUE
            var lastPushedAt = 0L

            player.state.collect { state ->
                if (!state.idle && state.source != null) sawMedia = true
                if (sawMedia && state.idle) {
                    // 播放已结束且没有新内容：没必要继续占着前台服务
                    stopPlayback()
                    return@collect
                }

                val key = SessionKey.of(state)
                val second = state.positionMs / 1000L
                val now = SystemClock.elapsedRealtime()
                val discreteChanged = key != lastKey
                val positionDue = second != lastSecond &&
                    now - lastPushedAt >= POSITION_PUSH_INTERVAL_MS

                if (discreteChanged || positionDue) {
                    lastKey = key
                    lastSecond = second
                    lastPushedAt = now
                    updateSession(state)
                    pushNotification(state)
                }
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

    /**
     * 通知里那几个 PendingIntent 的内容是**常量**，缓存起来。
     *
     * `PendingIntent.getActivity/getService` 每次都是一次到系统 ActivityManager
     * 的 binder 往返。原来每推一次通知就重建 5 个（1 个 content + 4 个 action），
     * 而通知推得又频繁 —— 白白制造大量主线程 IPC。
     */
    private val contentIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val previousIntent: PendingIntent by lazy { serviceIntent(ACTION_PREVIOUS, REQUEST_PREVIOUS) }
    private val toggleIntent: PendingIntent by lazy { serviceIntent(ACTION_TOGGLE, REQUEST_TOGGLE) }
    private val nextIntent: PendingIntent by lazy { serviceIntent(ACTION_NEXT, REQUEST_NEXT) }
    private val stopIntent: PendingIntent by lazy { serviceIntent(ACTION_STOP, REQUEST_STOP) }

    private fun buildNotification(state: PlayerState): Notification {
        val title = state.mediaTitle.ifBlank { state.source?.title ?: getString(R.string.app_name) }
        val subtitle = buildSubtitle(state)

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
                previousIntent,
            )
            .addAction(
                R.drawable.ic_notification,
                getString(if (state.paused) R.string.notif_action_play else R.string.notif_action_pause),
                toggleIntent,
            )
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.notif_action_next),
                nextIntent,
            )
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.notif_action_stop),
                stopIntent,
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

        /**
         * 纯进度变化时两次推送之间的最小间隔。
         *
         * 通知上的时间是「分:秒」，锁屏进度条也不需要更细 —— 1 秒一次足够，
         * 而 60Hz 推送会让主线程被 binder 调用淹掉（详见 onCreate 里的注释）。
         */
        private const val POSITION_PUSH_INTERVAL_MS = 1_000L

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

/**
 * 通知与会话里那些「离散」字段。
 *
 * 这些字段一变就必须**立刻**推送，不能等节流窗口 —— 否则按了暂停、切了文件，
 * 通知上的按钮和标题要过一秒才更新。
 *
 * 刻意**不含** `positionMs`：进度是连续变化的，它的推送由节流窗口控制。
 */
private data class SessionKey(
    val paused: Boolean,
    val buffering: Boolean,
    val seeking: Boolean,
    val idle: Boolean,
    val title: String,
    val durationMs: Long,
) {
    companion object {
        fun of(state: PlayerState) = SessionKey(
            paused = state.paused,
            buffering = state.buffering,
            seeking = state.seeking,
            idle = state.idle,
            title = state.mediaTitle.ifBlank { state.source?.title.orEmpty() },
            durationMs = state.durationMs,
        )
    }
}
