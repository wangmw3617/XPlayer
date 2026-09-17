import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// =============================================================================
//  本地属性
// =============================================================================

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String, default: String): String =
    (project.findProperty(key) as String?) ?: localProps.getProperty(key) ?: default

val xplayerAbis = prop("xplayer.abis", "arm64-v8a,armeabi-v7a,x86_64")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

// =============================================================================
//  版本号
//
//  优先级：环境变量 XPLAYER_VERSION_NAME（CI 从 tag 注入） > gradle.properties
//          > 兜底值。CI 发布时 workflow 把 v1.0.0 里的 1.0.0 通过环境变量传进来，
//  这样打 tag 就是唯一的发版动作。
//
//  versionCode 由 versionName 推导，保证单调递增（Android 要求新包更大）。
// =============================================================================

val fallbackVersionName = "1.0.0"
val versionNameValue = (
    System.getenv("XPLAYER_VERSION_NAME")?.takeIf { it.isNotBlank() }
        ?: prop("xplayer.versionName", fallbackVersionName)
    ).trim().removePrefix("v").ifBlank { fallbackVersionName }

// 1.0.3 -> 10003，1.2 -> 10002，1 -> 10000；非法输入退回 1
val versionCodeValue: Int = run {
    val parts = versionNameValue.split('.', '-', '+')
        .map { it.takeWhile(Char::isDigit) }
        .filter { it.isNotEmpty() }
        .map { it.toIntOrNull() ?: 0 }
    val code = parts.getOrElse(0) { 0 } * 10_000 +
        parts.getOrElse(1) { 0 } * 100 +
        parts.getOrElse(2) { 0 }
    if (code > 0) code else 1
}

// =============================================================================
//  发布签名
//
//  读取顺序：根目录 keystore.properties（本地构建，已 gitignore）→ 环境变量（CI 注入）。
//  两者都没有时 release 包保持「未签名」，这样没有密钥的人（包括 fork）也能构建。
// =============================================================================

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signProp(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

val releaseStoreFile = signProp("storeFile", "RELEASE_STORE_FILE")
val hasReleaseSigning: Boolean =
    !releaseStoreFile.isNullOrBlank() &&
        rootProject.file(releaseStoreFile).exists() &&
        !signProp("storePassword", "RELEASE_STORE_PASSWORD").isNullOrBlank() &&
        !signProp("keyAlias", "RELEASE_KEY_ALIAS").isNullOrBlank() &&
        !signProp("keyPassword", "RELEASE_KEY_PASSWORD").isNullOrBlank()

logger.lifecycle("XPlayer versionName = $versionNameValue  versionCode = $versionCodeValue")
logger.lifecycle("XPlayer abis = $xplayerAbis")
logger.lifecycle(
    if (hasReleaseSigning) {
        "Release 签名 = 已配置（${rootProject.file(releaseStoreFile!!).name}）"
    } else {
        "Release 签名 = 未配置（release 包将不签名）"
    },
)

android {
    namespace = "com.zhiwei.xplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zhiwei.xplayer"
        // libmpv 的 AAR 声明 minSdkVersion=26，低于它会在合并清单时报错
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeValue
        versionName = versionNameValue

        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "LIBMPV_VERSION", "\"${libs.versions.libmpv.get()}\"")
        buildConfigField("String", "LIQUID_GLASS_VERSION", "\"${libs.versions.liquidGlass.get()}\"")
    }

    // 按 ABI 拆包：每个 CPU 架构单独出一个 APK。
    // libmpv 的四套 .so 合计约 60MB，打进同一个包会得到一个近百兆的 APK；
    // 拆包后 arm64 单包约 25MB。isUniversalApk=false 表示不再额外产一个「全都有」的包。
    splits {
        abi {
            isEnable = true
            reset()
            include(*xplayerAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signProp("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signProp("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signProp("keyPassword", "RELEASE_KEY_PASSWORD")
                // APK Signature Scheme v1/v2/v3 全开：覆盖老设备，并支持密钥轮换
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // libmpv 的 .so 是 release 构建的，debug 下也不开 JNI 调试
            isJniDebuggable = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // 共享库按未压缩方式打包，由系统直接从 APK 加载，避免安装时解压一份副本。
            // libmpv AAR 自带 libc++_shared.so，其它依赖也可能带，取第一个即可。
            useLegacyPackaging = false
            pickFirsts += setOf("**/libc++_shared.so")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.kotlin_module",
            )
        }
    }

    androidResources {
        generateLocaleConfig = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // MediaSessionCompat + MediaStyle 通知（后台播放、锁屏与蓝牙控制）
    implementation(libs.androidx.media)

    // Liquid Glass（Kyant0/AndroidLiquidGlass，库名 backdrop）
    implementation(libs.kyant.backdrop)

    // 播放内核：libmpv（预编译 AAR，含四套 ABI 的 libmpv/libplayer/FFmpeg .so）
    implementation(libs.libmpv)

    testImplementation(libs.junit)
}
