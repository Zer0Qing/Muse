plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    // Phase 2.1: Kover — 插桩本模块字节码,数据上提到 root 聚合报告
    alias(libs.plugins.kover)
}

android {
    namespace = "io.zer0.common"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization (Json helper)
    implementation(libs.kotlinx.serialization.json)

    // 测试
    testImplementation(libs.junit)
}
