package com.zhiwei.xplayer

import com.zhiwei.xplayer.core.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Formatters] 的单元测试。
 *
 * 这些函数是纯函数（不碰 Context、不读系统时钟），所以普通 JVM 单元测试就能覆盖，
 * 不需要 Robolectric。时间码与体积格式在播放器里到处都在用，算错了会直接显示在
 * 用户面前，值得钉死。
 */
class FormattersTest {

    @Test
    fun `duration uses minutes below one hour`() {
        // 注意 duration(0) 是「时长未知」，返回占位符而不是 0:00，
        // 它的断言在 `unknown duration renders placeholder` 里
        assertEquals("0:05", Formatters.duration(5_000L))
        assertEquals("1:30", Formatters.duration(90_000L))
        assertEquals("59:59", Formatters.duration(3_599_000L))
    }

    @Test
    fun `duration switches to hours at one hour`() {
        assertEquals("1:00:00", Formatters.duration(3_600_000L))
        assertEquals("2:03:04", Formatters.duration(7_384_000L))
    }

    @Test
    fun `unknown duration renders placeholder`() {
        // duration 用于「总时长」，未知时是 0，应当显示占位而不是 0:00
        assertEquals("--:--", Formatters.duration(0L))
        assertEquals("--:--", Formatters.duration(-1L))
    }

    @Test
    fun `position clamps negatives to zero`() {
        assertEquals("0:00", Formatters.position(-5_000L))
        assertEquals("0:10", Formatters.position(10_400L))
    }

    @Test
    fun `size formats binary units`() {
        assertEquals("0 B", Formatters.size(0L))
        assertEquals("512 B", Formatters.size(512L))
        assertEquals("1.0 KB", Formatters.size(1024L))
        assertEquals("1.0 MB", Formatters.size(1024L * 1024L))
        assertEquals("1.5 GB", Formatters.size((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `relative time buckets`() {
        val now = 1_700_000_000_000L
        assertEquals("刚刚", Formatters.relativeTime(now - 10_000L, now))
        assertEquals("5 分钟前", Formatters.relativeTime(now - 5 * 60_000L, now))
        assertEquals("3 小时前", Formatters.relativeTime(now - 3 * 3_600_000L, now))
        assertEquals("2 天前", Formatters.relativeTime(now - 2 * 86_400_000L, now))
    }

    @Test
    fun `progress is clamped`() {
        assertEquals(0f, Formatters.progress(0L, 0L), 0.0001f)
        assertEquals(0.5f, Formatters.progress(50L, 100L), 0.0001f)
        assertEquals(1f, Formatters.progress(200L, 100L), 0.0001f)
    }

    @Test
    fun `speed trims trailing zeros`() {
        assertEquals("1x", Formatters.speed(1f))
        assertEquals("1.5x", Formatters.speed(1.5f))
        assertEquals("1.25x", Formatters.speed(1.25f))
        assertEquals("2x", Formatters.speed(2f))
    }

    @Test
    fun `delay always carries a sign`() {
        assertEquals("+0.00s", Formatters.delay(0.0))
        assertEquals("+1.50s", Formatters.delay(1.5))
        assertEquals("-0.50s", Formatters.delay(-0.5))
    }

    @Test
    fun `extension detection drives media filtering`() {
        assertEquals("mp4", Formatters.extensionOf("Movie.Final.MP4"))
        assertEquals("", Formatters.extensionOf("noextension"))
        assertEquals("", Formatters.extensionOf("trailing."))
        assertTrue(Formatters.isVideoFile("a.mkv"))
        assertTrue(Formatters.isMediaFile("a.flac"))
        assertFalse(Formatters.isMediaFile("a.txt"))
        assertTrue(Formatters.isSubtitleFile("a.zh.srt"))
        assertFalse(Formatters.isSubtitleFile("a.mp4"))
    }

    @Test
    fun `base name strips only the last extension`() {
        assertEquals("movie.2024", Formatters.baseName("movie.2024.mkv"))
        assertEquals("noext", Formatters.baseName("noext"))
    }
}
