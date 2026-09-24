package com.zhiwei.xplayer

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `derivedStateOf` 能观察到什么、不能观察到什么。
 *
 * 这一组测试是**回归测试**，来源是播放页真实的卡顿 bug：
 *
 * ```
 * val state = viewModel.state                 // StateFlow<PlayerState>
 * val paused by remember { derivedStateOf { state.value.paused } }
 * ```
 *
 * 这段代码能编译、能在界面上显示，但**永远不更新** —— 因为
 * `StateFlow.value` 只是一次普通字段读取，不是 Compose 的快照状态，
 * `derivedStateOf` 记录不到任何依赖，算完一次之后就一直返回旧值。
 * 表现就是「点了播放按钮没反应 / 界面像卡住了」。
 *
 * 正确写法是先 `collectAsState`（或 `collectAsStateWithLifecycle`）拿到
 * Compose 的 [State]，再在它上面派生。
 */
class ComposeStateObservationTest {

    /** 对照组：派生自 Compose [State] —— 会跟着更新。 */
    @Test
    fun derivedFromComposeState_updates() {
        val source = mutableStateOf(1)
        val derived = derivedStateOf { source.value * 10 }

        assertEquals(10, derived.value)

        source.value = 2
        Snapshot.sendApplyNotifications()

        assertEquals("Compose State 是快照状态，派生值必须跟着变", 20, derived.value)
    }

    /**
     * 这一条锁住 bug 本身：派生自 `StateFlow` 的值**不会**更新。
     *
     * 断言的是「错误行为」，看着别扭，但这是故意的 —— 它是一条
     * 特征测试（characterization test），把 Compose 的这条语义固定在仓库里，
     * 免得以后有人又写出 `derivedStateOf { someFlow.value.x }`。
     * 如果哪天 Compose 真改成能观察 StateFlow，这条会红，
     * 那时正好提醒我们回来重新评估。
     */
    @Test
    fun derivedFromStateFlow_doesNotUpdate() {
        val source = MutableStateFlow(1)
        val derived = derivedStateOf { source.value * 10 }

        assertEquals(10, derived.value)

        source.value = 2
        Snapshot.sendApplyNotifications()

        assertEquals(
            "StateFlow.value 不是快照状态，derivedStateOf 观察不到它 —— " +
                "这正是播放页状态不更新的根因；" +
                "要观察 StateFlow 请先 collectAsState 再派生",
            10,
            derived.value,
        )
    }

    /**
     * 反过来确认 [State] 包一层之后就能观察了 —— 也就是修复方案本身。
     */
    @Test
    fun collectingStateFlowThenDeriving_updates() {
        val flow = MutableStateFlow(1)
        // 模拟 collectAsState：StateFlow 的值被写进一个 Compose State
        val collected = mutableStateOf(flow.value)
        val derived = derivedStateOf { collected.value * 10 }

        assertEquals(10, derived.value)

        flow.value = 2
        collected.value = flow.value
        Snapshot.sendApplyNotifications()

        assertEquals("经由 Compose State 中转后就能正常观察", 20, derived.value)
    }

    /**
     * 派生值没变时不应触发读者更新 —— 这是「按字段拆开」能省掉重组的依据：
     * `time-pos` 每秒推几十次，但 `paused` 的派生值一直没变，
     * 于是播放按钮不会被牵连重组。
     */
    @Test
    fun derivedValueUnchanged_isNotAChange() {
        data class Snap(val position: Long, val paused: Boolean)

        val source = mutableStateOf(Snap(0L, true))
        val derived = derivedStateOf { source.value.paused }

        assertEquals(true, derived.value)

        // 只改进度，不改 paused
        source.value = Snap(500L, true)
        Snapshot.sendApplyNotifications()

        assertEquals("进度变了但 paused 没变，派生值应当还是 true", true, derived.value)

        source.value = Snap(1000L, false)
        Snapshot.sendApplyNotifications()

        assertEquals("paused 真的翻转了才应当变", false, derived.value)
    }
}
