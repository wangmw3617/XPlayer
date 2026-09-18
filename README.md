# XPlayer

基于 **libmpv** 的安卓全能播放器。播放内核与架构参考 [mpv-android](https://github.com/mpv-android/mpv-android)，
界面用 **Jetpack Compose + Material 3** 重写，底栏采用液态玻璃（Liquid Glass）效果。

- **播放内核**：libmpv 1.0.0（`dev.jdtech.mpv:libmpv`），MediaCodec 硬解，`gpu-next` / `gpu` 双渲染后端
- **界面**：100% Compose，Material You 动态取色，液态玻璃悬浮底栏与播放控制层
- **架构**：单 Activity + Navigation Compose 类型安全路由；Hilt 依赖注入；Room 存历史与授权目录；DataStore 存设置
- **最低版本**：Android 8.0（API 26，由 libmpv AAR 的 `minSdkVersion` 决定）
- **目标版本**：Android 16（API 36）

---

## 1. 功能

### 播放

| 能力 | 说明 |
| --- | --- |
| 硬解 | `hwdec=mediacodec,mediacodec-copy`，覆盖 H.264 / HEVC / MPEG-4 / MPEG-2 / VP8 / VP9 / AV1 |
| 渲染后端 | `gpu-next`（libplacebo，色彩与 HDR 更好）与 `gpu` 可切换，设置里改完立即生效 |
| 容器 / 编码 | 交给 libmpv + FFmpeg，mkv / mp4 / ts / flv / webm / rmvb / mxf … 以及几乎所有常见音频格式 |
| 网络串流 | `http(s)://`、`rtsp://`、`rtmp(s)://`、`rtp://`、`udp://`、`tcp://`、`ftp://`（内置 mbedTLS） |
| 字幕 | 自动加载同名字幕（`sub-auto=fuzzy`，支持 `.zh.srt` 之类的语言后缀）、手动加载外部字幕、字幕延迟 ±2s |
| 音轨 | 多音轨切换与关闭、首选音轨语言（`alang`）、音频延迟 ±2s |
| 画面 | 双指缩放（1×–5×）、旋转、画面比例（跟随视频 / 拉伸铺满 / 16:9 / 4:3 / 21:9 / 1:1） |
| 倍速 | 0.25× – 4×，长按临时倍速（默认 3×） |
| 循环 | 不循环 / 列表循环 / 单曲循环 |
| 截图 | 保存到应用专属外部目录（`Android/data/com.zhiwei.xplayer/files/Pictures`），不需要存储权限 |
| 画中画 | Android 8.0+，按视频宽高设置 PiP 比例 |
| 后台播放 | mediaPlayback 前台服务 + MediaSessionCompat，锁屏与耳机按键可控 |

### 手势

| 手势 | 行为 |
| --- | --- |
| 单击 | 显示 / 隐藏控制层 |
| 双击 | 播放 / 暂停 |
| 长按 | 临时切到长按倍速，松手恢复 |
| 左半屏上下滑 | 亮度（改的是窗口 `screenBrightness`，不动系统设置） |
| 右半屏上下滑 | 音量（0–150%，可超过 100%） |
| 横向滑动 | 快进 / 快退，一屏宽度对应「设置 → 手势 → 横向滑动单位」秒数，拖动过程实时跳转 |
| 双指捏合 | 画面缩放 |

### 媒体管理

- **媒体库**：MediaStore 扫描本机视频与音频，网格 / 列表切换，按添加时间 / 名称 / 时长 / 大小排序，标题搜索
- **文件夹**：SAF 授权任意目录（`OpenDocumentTree`），逐级浏览，权限持久化
- **播放历史**：自动记录进度，5 秒节流写库 + 暂停 / 结束 / 退出时补写；「继续观看」一键续播
- **网络串流**：地址输入 + 最近播放列表

---

## 2. 构建

### 2.1 本地构建（可选）

需要 JDK 17+ 与 Android SDK（platform 36 / build-tools 36.0.0）。

```bash
./gradlew assembleDebug
```

发布签名走根目录的 `keystore.properties`（已 gitignore）：

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

没有这个文件时 release 包会保持**未签名**，构建不会失败 —— 这样 fork 的人不需要密钥也能编译。

### 2.2 CI 构建（本项目的默认方式）

`.github/workflows/build.yml` 定义四个 job：

```
lint（静态检查，秒级）
  ├── test（单元测试）  ─┐
  └── build（打包 APK）  ─┴──> release（仅 tag 触发，创建 GitHub Release）
```

- push 到 `main`：跑 lint / test / build，产出 artifact
- push tag `v1.0.0`：额外恢复签名密钥、校验签名、把 APK 附到 Release
- 纯文档改动不会触发（`paths-ignore`）

**打 tag 就是唯一的发版动作**，不需要改代码：workflow 从 tag 名取出 `1.0.0` 并通过
`XPLAYER_VERSION_NAME` 环境变量传给 Gradle，`versionCode` 由 `versionName` 推导。

发布签名需要四个仓库 Secret：

| Secret | 内容 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `release.jks` 的 base64 |
| `RELEASE_STORE_PASSWORD` | keystore 口令 |
| `RELEASE_KEY_ALIAS` | 密钥别名 |
| `RELEASE_KEY_PASSWORD` | 密钥口令 |

本地生成并写入 Secret：

```bash
keytool -genkeypair -v -keystore release.jks -storetype JKS \
  -alias xplayer -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=XPlayer, OU=Mobile, O=zhiwei, L=Beijing, ST=Beijing, C=CN"

base64 -w0 release.jks > release.jks.base64   # 内容填进 RELEASE_KEYSTORE_BASE64
```

### 2.3 ABI 拆包

`gradle.properties` 的 `xplayer.abis` 控制产物：

```
arm64-v8a      真机主力
armeabi-v7a    32 位真机
x86_64         模拟器
```

libmpv 的四套 `.so` 合计约 60MB，打进同一个包会得到近百兆的 APK；拆包后 arm64 单包约 25MB。
`x86`（32 位模拟器）已被剔除。

---

## 3. 代码结构

```
app/src/main/java/com/zhiwei/xplayer/
├── XPlayerApp.kt                   Hilt Application（刻意不在这里初始化 mpv）
├── MainActivity.kt                 单 Activity，处理分享 / 打开方式意图
├── di/AppModule.kt                 Room 的依赖提供
├── core/
│   ├── mpv/
│   │   ├── MpvPlayer.kt            libmpv 封装：生命周期、属性观察、命令、轨道
│   │   ├── MpvTypes.kt             PlayerState / MpvTrack / PlaybackSource / 常量
│   ├── media/
│   │   ├── MediaRepository.kt      MediaStore 扫描与权限判断
│   │   ├── MediaEntry.kt           媒体条目与排序枚举
│   │   ├── Thumbnails.kt           缩略图（loadThumbnail / MediaMetadataRetriever + LRU）
│   │   ├── SafBrowser.kt           SAF 目录浏览
│   ├── data/
│   │   ├── SettingsRepository.kt   DataStore + 同步快照
│   │   ├── LibraryRepository.kt    历史 / 授权目录 / 播放列表
│   │   └── db/AppDatabase.kt       Room 实体、DAO、数据库
│   ├── playback/PlaybackService.kt 前台服务 + MediaSession + 通知
│   └── util/Formatters.kt          纯函数格式化（有单元测试）
└── ui/
    ├── AppRoot.kt                  液态玻璃悬浮底栏 + NavHost
    ├── nav/Routes.kt               类型安全路由（@Serializable object）
    ├── theme/LiquidGlass.kt        backdrop 库的封装：采样层 / 玻璃层 / 兜底
    ├── theme/Theme.kt              Material 3 主题与动态取色
    ├── components/                 通用组件、选择器
    ├── screen/                     首页 / 媒体库 / 文件夹 / 设置 / 串流 / 历史 / 日志
    ├── player/                     播放页、SurfaceView、控制层、选项面板
    └── vm/                         三个 ViewModel
```

### 3.1 液态玻璃的三个概念

`backdrop` 只提供底层原语，没有现成组件，用的时候必须凑齐三件事：

1. `rememberAppBackdrop()` 建一个背景层；
2. `Modifier.layerBackdrop(backdrop)` 标记**哪些内容会被玻璃采样**；
3. `Modifier.liquidGlass(...)` 才是玻璃本身。

**采样层与玻璃层必须是兄弟节点，且玻璃排在后面。** 玻璃若被包在采样层内部，
采到的是它自己 → 递归，什么都画不出来。所以 `AppBackground` 只负责「渐变 + 页面内容」，
底栏写在它外面。

另外，玻璃的本质是「模糊并折射下面的东西」，纯色或平滑渐变模糊前后几乎没差别，
看上去就是块半透明色板。因此背景特意叠了两个很淡的径向色斑，给模糊提供可分辨的层次。

`Modifier.liquidGlass` 还做了一件事：形状不是 `CornerBasedShape` 时**跳过 lens 效果**。
库里拿不到圆角半径会直接 `throw`，而这发生在绘制阶段 —— 不是「效果没生效」，
是主线程崩、应用秒退。

### 3.2 libmpv 的生命周期

```
MPVLib.create(ctx) → 设选项 → MPVLib.init() → 设硬编码选项 → 注册观察者
```

顺序不能乱：`setOptionString` 只在 `create` 与 `init` 之间生效，观察者也必须在
`init` 之后注册才收得到事件。

`MpvPlayer` 是应用级单例，**不在 Application 里初始化**（冷启动建 mpv 会拖慢启动，
而用户可能只是打开看一眼设置），第一次真正要用时才惰性创建。

视频输出靠 `vo` 在 `gpu-next` 与 `null` 之间切换，而不是在没 surface 时停掉播放 ——
这样锁屏 / 切后台时音频继续，回到前台画面立刻回来。

### 3.3 content:// 的处理

不把 `content://` 解析成真实路径。Android 11+ 的 scoped storage 下，MediaStore 里的文件
即使能拿到 `/storage/emulated/0/...` 这样的路径，应用也**没有权限直接读**（只有
`READ_MEDIA_VIDEO` 这类按类型授权的权限，不等于原始路径读权限）。

正确做法是直接把 `content://` 交给 mpv：FFmpeg 内置 `android_content` 协议
（`libavformat` 里的 `content` 协议），通过 `ContentResolver` 读取，在 scoped storage
下工作正常，并且支持 seek。本项目的 AAR 里确认包含该协议。

---

## 4. 已知取舍

- **不做本地源码构建 libmpv**。mpv-android 的 `buildscripts` 需要 Nix + 完整 FFmpeg/mpv
  交叉编译，CI 上动辄一小时以上且极易受上游漂移影响。改用 `dev.jdtech.mpv:libmpv`
  预编译 AAR（含四套 ABI、`gpu-next`、libplacebo、libass、mbedTLS）。
- **libmpv 用 1.0.0，且调用方式是实例式的**。0.4.x 的 `MPVLib` 是一堆 Java 静态方法；
  1.0.0 改成「`MPVLib.create(ctx)` 返回实例 + 实例方法」，常量也搬进了
  `MPVLib.MpvFormat` / `MPVLib.MpvEvent` / `MPVLib.MpvLogLevel` 三个嵌套对象。
  `MpvPlayer` 因此持有 `lib` 实例，所有调用点写成 `mpv?.xxx(...)`。
  1.0.0 的 AAR 还要求 `minCompileSdk=36`（本项目正好是 36）。
- **不使用 Coil**。本项目只需要「给一个 `content://` 取一张位图」这一种能力，
  而 Coil 3.x 的 `ImageLoader` / Decoder 工厂 API 改动频繁，为一张缩略图承担那套
  版本风险不划算。`Thumbnails` 用系统 API + LRU 直接实现。
- **Liquid Glass 固定在 `backdrop 1.0.2`**。2.x 要求 `minCompileSdk=37`；
  1.0.3–1.0.6 要求 Kotlin ≥2.3，会把 Hilt 与 AGP 一起顶上去形成连锁升版。
  1.0.2 是唯一「Kotlin 2.2.21 + 不带 shapes」的版本。
- **R8 开启**。libmpv 的 AAR 自带 `-keep class dev.jdtech.mpv.MPVLib { *; }`，
  本项目再补了整个包与 native 方法名，避免 JNI 反查失败。

---

## 5. 许可

GPL-3.0。libmpv / mpv 与 FFmpeg 的许可条款见各自上游项目。
