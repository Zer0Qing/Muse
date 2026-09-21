plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
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

// P4-5: 覆盖率门禁补全 — accessibility 此前无 verify 规则(静默不算数)。
// 按当前实测 LINE 1.7% 设定(服务多为 AIDL/系统绑定路径,单测难覆盖),低于该值即失败。
kover {
    reports {
        verify {
            rule {
                minBound(2)
            }
        }
    }
}
