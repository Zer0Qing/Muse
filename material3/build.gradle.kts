// v1.97 gap7: :material3 模块 — Material Color Utilities 桥接层。
//
// 职责:
//  - 通过 kotlin.srcDir 直接挂载 material-color-utilities 上游 Kotlin 源码
//    (Google material-foundation/material-color-utilities,Apache 2.0)
//  - 提供 DynamicScheme.toColorScheme() 扩展,将 HCT 色彩空间生成的方案
//    转换为 Compose Material3 的 ColorScheme,供 CustomTheme.generateColorScheme 使用
//
// 设计决策:
//  - 不通过 Maven 依赖引入,因为 com.google.android.material:material-color-utilities
//    未发布到 Maven Central(2026-07 验证);rikkahub 也采用源码直挂方式
//  - 上游源码保留在 material-color-utilities/kotlin/ 子目录,便于版本升级时整体替换
//  - 模块本身是 Android Library(需要 Compose ColorScheme/Color 类型),不是纯 JVM

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.zer0.material3"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        named("main") {
            // 将 material-color-utilities 上游 Kotlin 源码挂载到编译路径
            kotlin.srcDir("material-color-utilities/kotlin")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
}
