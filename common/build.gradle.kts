plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    // Phase 2.1: Kover — 插桩本模块字节码,数据上提到 root 聚合报告
    alias(libs.plugins.kover)
}

android {
    namespace = "io.zer0.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // 单元测试里 `android.util.Log` 是 stub：不开这项，任何走到 Logger/Perf
        // 的用例都会因 "Stub!" 直接抛，于是这两个全仓共用的件在本模块**根本没法测**
        // （`:common` 之前 51 例里全是纯函数用例，就是这个原因）。
        // app / memory / miniphone / ai 四个模块早就这么配了，这里只是对齐。
        unitTests.isReturnDefaultValues = true
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
