# =============================================================================
#  XPlayer R8 规则
#
#  绝大多数 keep 规则由依赖自带的 consumer rules 提供（Hilt / Room / Compose /
#  kotlinx-serialization / androidx.media 都会随 AAR 带上 proguard.txt），
#  这里只补它们没覆盖、或本项目特有的部分。
# =============================================================================

# ---- libmpv JNI ----
# AAR 自己的 proguard.txt 里已有 `-keep class dev.jdtech.mpv.MPVLib { *; }`，
# 但 native 侧是通过 JNI 名字反查这个类的静态方法，任何成员被裁剪或改名都会
# 在运行时抛 NoSuchMethodError，所以这里连整个包一起保留，并显式保留 native 方法。
-keep class dev.jdtech.mpv.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---- MediaSession / 通知 ----
# 通知里的 PendingIntent 指向 PlaybackService，Service 名被混淆后系统找不到。
-keep class com.zhiwei.xplayer.core.playback.PlaybackService { *; }

# ---- 枚举 ----
# Room / DataStore / 反射用到的枚举 valueOf 依赖枚举常量名不被改写。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- Parcelable / Serializable ----
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ---- 调试信息 ----
# 保留行号，线上崩溃栈才能对上源码；源码文件名混淆掉即可。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 移除日志（release 下把 Log.v/d 调用直接消掉，减小体积）----
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
