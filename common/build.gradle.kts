plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
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

// P4-5: 覆盖率门禁补全 — common 此前无 verify 规则(静默不算数)。
// 按当前实测 LINE 22.6% 设定,低于该值即失败。
kover {
    reports {
        verify {
            rule {
                minBound(65)
            }
        }
    }
}
