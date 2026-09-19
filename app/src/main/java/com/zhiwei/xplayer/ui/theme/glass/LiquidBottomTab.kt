package com.zhiwei.xplayer.ui.theme.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 底栏项的缩放系数。
 *
 * 用手指按压底栏时，被采样层里的图标会整体放大一点 ——
 * 指示器折射过去时就会呈现「图标被放大」的错觉，这是原版最抓眼的一处细节。
 */
internal val LocalLiquidBottomTabScale = staticCompositionLocalOf { { 1f } }

/**
 * 胶囊形。等价于 `com.kyant.shapes.Capsule()`。
 *
 * 为什么不用上游的 `Capsule`：它来自 `io.github.kyant0:shapes-android`，
 * 而那个库的 POM 要求 `kotlin-stdlib 2.3.0`，会把整个工具链链条
 * （Kotlin → Hilt → AGP）一起顶上去。这个项目必须停在 Kotlin 2.2.21，
 * 所以宁可自己写一行 `RoundedCornerShape(percent = 50)` —— 两者都是
 * `CornerBasedShape` 的子类，在 backdrop 的 lens 折射里走的是同一个分支，
 * 视觉与行为完全一致。
 */
val CapsuleShape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(percent = 50)

/**
 * 一个底栏项。
 *
 * `indication = null`：不要 Material 的水波纹。液态玻璃按下时的反馈来自
 * [InteractiveHighlight] 的柔光与指示器的形变，再叠一层涟漪只会互相打架。
 *
 * 移植自 `Kyant0/AndroidLiquidGlass` 的 `catalog/components/LiquidBottomTab.kt`。
 */
@Composable
fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(CapsuleShape)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
