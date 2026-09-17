// 顶层构建脚本：只声明插件，不配置具体行为。
// 所有插件都 `apply false`，由各模块按需启用，避免根项目被无谓地参与配置阶段。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
