package com.zhiwei.xplayer

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.zhiwei.xplayer.core.mpv.PlaybackSource
import com.zhiwei.xplayer.core.mpv.PlayerState
import com.zhiwei.xplayer.ui.player.PlayerStateObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlayerStateObserver] 的行为测试。
 *
 * 这组测试是「播放按钮卡顿」那个 bug 的回归护栏，锁住三件事：
 *
 * 1. 字段**真的会**跟着快照更新（修之前不会，这是根因）；
 * 2. 进度按 200ms 量化（避免每秒重组几十次进度条）；
 * 3. 进度变化**不会**动到 `paused` 这类别的字段（按字段拆开的意义）。
 */
class PlayerStateObserverTest {

    /** 把观察器包一层，省得每个用例都写一遍 `mutableStateOf(PlayerState())` */
    private fun observerOf(initial: PlayerState = PlayerState()): Pair<MutableState<PlayerState>, PlayerStateObserver> {
        val raw = mutableStateOf(initial)
        return raw to PlayerStateObserver(raw)
    }

    private fun MutableState<PlayerState>.put(next: PlayerState) {
        value = next
        Snapshot.sendApplyNotifications()
    }

    /** 根因回归：快照变了，派生字段必须跟着变。 */
    @Test
    fun fieldsFollowSnapshot() {
        val (raw, player) = observerOf()

        assertFalse("初始没有媒体", player.hasMedia)
        assertEquals("", player.title)

        raw.put(
            PlayerState(
                source = PlaybackSource(uri = "file:///movie.mp4", title = "movie.mp4"),
                idle = false,
                paused = false,
                durationMs = 60_000L,
                positionMs = 5_000L,
                videoWidth = 1920,
                videoHeight = 1080,
            ),
        )

        assertTrue("时长解出后 hasMedia 必须为真", player.hasMedia)
        assertFalse("暂停状态必须跟着快照走", player.paused)
        assertEquals(60_000L, player.durationMs)
        assertEquals("movie.mp4", player.title)
        assertEquals("0:05 / 1:00 · 1920×1080", player.subtitle)
    }

    /** 进度量化：避免 time-pos 每帧变化都驱动一次重组。 */
    @Test
    fun positionIsQuantized() {
        val (raw, player) = observerOf()

        val cases = listOf(
            0L to 0L,
            199L to 0L,
            200L to 200L,
            1_234L to 1_200L,
            1_999L to 1_800L,
            60_000L to 60_000L,
        )
        for ((input, expected) in cases) {
            raw.put(raw.value.copy(positionMs = input))
            assertEquals("positionMs=$input 应量化到 $expected", expected, player.positionMs)
        }
    }

    /** 按字段拆开的意义：进度在走，播放按钮看到的派生值不动。 */
    @Test
    fun positionChangeDoesNotTouchPaused() {
        val (raw, player) = observerOf(PlayerState(paused = true))

        assertTrue(player.paused)

        // 连推几次进度，模拟 time-pos 高频更新
        for (position in listOf(200L, 400L, 600L, 800L)) {
            raw.put(raw.value.copy(positionMs = position))
            assertTrue("进度变化不该影响 paused", player.paused)
        }
    }

    /** 暂停翻转要能被观察到（这是播放按钮图标切换的依据）。 */
    @Test
    fun pausedFlipIsObserved() {
        val (raw, player) = observerOf(PlayerState(paused = true))

        assertTrue(player.paused)
        raw.put(raw.value.copy(paused = false))
        assertFalse(player.paused)
        raw.put(raw.value.copy(paused = true))
        assertTrue(player.paused)
    }

    /** 缓冲判定：mpv 明说在缓冲，或文件已加载但时长还没解出来。 */
    @Test
    fun bufferingCoversDurationNotReady() {
        val (raw, player) = observerOf()

        // 空闲且没有时长：不算缓冲（否则空界面会一直转圈）
        assertFalse(player.buffering)

        // 已开始播放但时长还没解出来：算缓冲
        raw.put(PlayerState(idle = false, durationMs = 0L))
        assertTrue(player.buffering)

        // 时长解出来了：不缓冲
        raw.put(PlayerState(idle = false, durationMs = 60_000L))
        assertFalse(player.buffering)

        // mpv 明确报缓冲：缓冲
        raw.put(PlayerState(idle = false, durationMs = 60_000L, buffering = true))
        assertTrue(player.buffering)
    }

    /** 换文件时 sourceUri 变化 —— 播放页用它做 key 复位画面参数。 */
    @Test
    fun sourceUriFollowsSource() {
        val (raw, player) = observerOf()
        assertEquals(null, player.sourceUri)

        raw.put(PlayerState(source = PlaybackSource(uri = "content://a", title = "a")))
        assertEquals("content://a", player.sourceUri)
    }

    /** 时长未知时不显示副标题，避免出现 `0:00 / --:--` 这种噪音。 */
    @Test
    fun subtitleIsBlankBeforeDurationKnown() {
        val (raw, player) = observerOf()
        raw.put(PlayerState(idle = false, positionMs = 3_000L, durationMs = 0L))
        assertEquals("", player.subtitle)
    }
}
