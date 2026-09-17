package com.zhiwei.xplayer.ui.player

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.zhiwei.xplayer.core.mpv.MpvPlayer

/**
 * libmpv 的视频输出载体。
 *
 * 用 [SurfaceView] 而不是 `TextureView`：SurfaceView 的画面由 SurfaceFlinger 直接
 * 合成，不经过应用的 UI 线程与 GPU 绘制管线，功耗更低、也没有 TextureView 那种
 * 「先渲染到纹理再贴到界面」的额外拷贝 —— 播 4K 时差别很明显。
 *
 * 三个回调与 mpv 的对应关系（顺序不能改）：
 *
 * - `surfaceCreated` → `attachSurface` 并把 `vo` 打开；
 * - `surfaceChanged` → 把尺寸同步给 mpv（影响 OSD 缩放与画面比例换算）；
 * - `surfaceDestroyed` → 先 `vo=null` 再 `detachSurface`，保证 mpv 不再持有这张
 *   surface 之后系统才回收它，否则会出现「用已释放的 surface 渲染」的崩溃。
 */
class MpvSurfaceView(
    context: Context,
    private val player: MpvPlayer,
) : SurfaceView(context), SurfaceHolder.Callback {

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        player.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        player.setSurfaceSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        player.detachSurface()
    }
}
