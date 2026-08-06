plugins {
    alias(libs.plugins.android.library)
    // R-BUILD-09: Kover 插桩,纳入 root 覆盖率聚合
    alias(libs.plugins.kover)
}

android {
    namespace = "io.zer0.muse.accessibility"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    // P3-3: 显式启用 AIDL — MuseAccessibilityService(服务端)与 AccessibilityClient(客户端)
    // 共享 IAccessibilityProvider 接口,通过 buildFeatures.aidl 生成 Stub/Proxy
    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
