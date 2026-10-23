package com.zhiwei.xplayer

import com.zhiwei.xplayer.core.mpv.PlaybackSource
import com.zhiwei.xplayer.core.playback.PlaybackQueueNavigator
import com.zhiwei.xplayer.core.playback.PlaybackQueueState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueNavigatorTest {

    private val items = listOf(
        PlaybackSource(uri = "content://one", title = "one"),
        PlaybackSource(uri = "content://two", title = "two"),
        PlaybackSource(uri = "content://three", title = "three"),
    )

    @Test
    fun `next follows queue order without wrapping by default`() {
        val middle = PlaybackQueueState(items = items, index = 1, loopMode = 0)

        assertEquals(2, PlaybackQueueNavigator.next(middle))
        assertNull(PlaybackQueueNavigator.next(middle.copy(index = 2)))
        assertTrue(middle.hasNext)
    }

    @Test
    fun `list loop wraps in both directions`() {
        val atEnd = PlaybackQueueState(items = items, index = 2, loopMode = 1)
        val atStart = atEnd.copy(index = 0)

        assertEquals(0, PlaybackQueueNavigator.next(atEnd))
        assertEquals(2, PlaybackQueueNavigator.previous(atStart))
        assertTrue(atEnd.hasNext)
        assertTrue(atStart.hasPrevious)
    }

    @Test
    fun `natural end does not advance in single-file loop mode`() {
        val singleLoop = PlaybackQueueState(items = items, index = 1, loopMode = 2)

        assertNull(PlaybackQueueNavigator.afterNaturalEnd(singleLoop))
        assertEquals(2, PlaybackQueueNavigator.next(singleLoop))
    }

    @Test
    fun `natural end advances until the end of the queue`() {
        val state = PlaybackQueueState(items = items, index = 0, loopMode = 0)

        assertEquals(1, PlaybackQueueNavigator.afterNaturalEnd(state))
        assertNull(PlaybackQueueNavigator.afterNaturalEnd(state.copy(index = items.lastIndex)))
        assertFalse(state.hasPrevious)
    }

    @Test
    fun `previous at first item restarts it outside list loop`() {
        val state = PlaybackQueueState(items = items, index = 0, loopMode = 0)

        assertEquals(0, PlaybackQueueNavigator.previous(state))
        assertFalse(state.hasPrevious)
    }
}
