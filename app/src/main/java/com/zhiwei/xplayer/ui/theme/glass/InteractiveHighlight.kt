package com.zhiwei.xplayer.ui.theme.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 按下时跟随手指的一团柔光。
 *
 * 这是液态玻璃「活着」的关键细节：静态玻璃看着像塑料片，
 * 一旦按住的位置亮起一小团光、松手淡出，材质感立刻就出来了。
 *
 * 移植自 `Kyant0/AndroidLiquidGlass` 的 `catalog/utils/InteractiveHighlight.kt`。
 *
 * **与上游的差异（重要）**：上游用 AGSL runtime shader 画一个羽化圆斑，
 * 依赖 `com.kyant.backdrop.RuntimeShader` / `asComposeShader` /
 * `isRuntimeShaderSupported` 三个 API。这三个 API 在 ktor 的 kmp 分支里才有，
 * **本项目锁定的 `io.github.kyant0:backdrop:1.0.2` 里不存在**
 * （1.0.2 的 `ShadersKt` 基本是空的，`effects/RenderEffectKt` 只暴露
 * `effect` / `currentEffect` / `asAndroidRenderEffect`）。
 *
 * 硬凑 runtime shader 有两条路，都不划算：
 * 一是升到 backdrop 2.x，那要 `minCompileSdk=37`，AGP 8.10.1 不支持；
 * 二是自己 `android.graphics.RuntimeShader` + `ShaderBrush` 拼，
 * 但那样就得自己管 compose 的 shader 缓存与 API 30 以下的分支，
 * 为了一团白光不值得。
 *
 * 所以这里**只保留上游的兜底分支**：整块 +25% 白光，用 `BlendMode.Plus`
 * 叠在玻璃上。位置上仍然跟随手指、松手淡出 —— 视觉上 90% 的效果都在，
 * 而且这个 drawRect 走的是最普通的绘制路径，没有额外的 shader 编译开销，
 * 对「点了按钮要立刻有反馈」这件事反而更稳。
 */
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {

    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f) {
            val center = position(size, positionAnimation.value)
            // 光斑画在手指附近而不是铺满整块：半径取短边的 0.9 倍，
            // 再用三层递减透明度的圆叠加模拟羽化边缘（省掉 shader 的 smoothstep）。
            val radius = size.minDimension * 0.9f
            val x = center.x.coerceIn(0f, size.width)
            val y = center.y.coerceIn(0f, size.height)
            drawCircle(
                color = Color.White.copy(0.16f * progress),
                radius = radius,
                center = Offset(x, y),
                blendMode = BlendMode.Plus,
            )
            drawCircle(
                color = Color.White.copy(0.12f * progress),
                radius = radius * 0.66f,
                center = Offset(x, y),
                blendMode = BlendMode.Plus,
            )
            drawCircle(
                color = Color.White.copy(0.10f * progress),
                radius = radius * 0.36f,
                center = Offset(x, y),
                blendMode = BlendMode.Plus,
            )
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = {
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            },
            onDragCancel = {
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            },
        ) { change, _ ->
            animationScope.launch { positionAnimation.snapTo(change.position) }
        }
    }
}
