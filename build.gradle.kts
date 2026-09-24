// 顶层构建脚本：只声明插件，不配置具体行为。
// 所有插件都 `apply false`，由各模块按需启用，避免根项目被无谓地参与配置阶段。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// =============================================================================
//  把内置 Kotlin 的版本抬到 2.3.21
//
//  AGP 9 起 Kotlin 编译内置，不再应用 org.jetbrains.kotlin.android 插件。
//  但「内置」不等于版本不可控 —— AGP 只是在自己的 classpath 上依赖了
//  kotlin-gradle-plugin:2.2.10 作为**下限**，Gradle 的依赖仲裁取最高版本。
//  所以在这里显式声明 KGP 2.3.21，内置 Kotlin 就跟着到 2.3.21。
//
//  为什么必须抬：
//    · Compose 编译器插件与 serialization 插件必须与 Kotlin **严格同版本**，
//      而这两个插件的版本由版本目录的 `kotlin` 决定（2.3.21）。
//      不抬的话就是「编译器 2.2.10 + 插件 2.3.21」，Compose 插件会直接报版本不符。
//    · KSP 2.3.10 才修掉「AGP 9 内置 Kotlin 下 R 类解析」（google/ksp#2857），
//      而 Hilt 与 Room 生成的代码都要引用 R 类。
//
//  这不是临时补丁，而是「用内置 Kotlin 同时自选 Kotlin 版本」的可行做法。
// =============================================================================
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}
