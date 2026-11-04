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
        // 的用例都会因 "Stub!" 直接抛，于是这两个全仓共用的件在本模块**根本没法测**。
        // app / memory / miniphone / ai 四个模块早就这么配了，这里只是对齐。
        unitTests {
            isReturnDefaultValues = true
            // Robolectric：`Logger` 的三份出口里有两份要真环境才能验
            // （文件出口需要能用的 `cacheDir`；Logcat 出口要靠 ShadowLog 抓到调用）。
            // 以前只能在纯函数层测 `sanitizePii`，"出口真的拿到脱敏视图"只能靠读代码断言。
            isIncludeAndroidResources = true
            // Robolectric 4.16+ 在 Java 21 下需要开放 JDK 内部模块（与 memory 同一份清单）。
            all {
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                )
            }
        }
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
    testImplementation(libs.robolectric)
}

// P4-5: 覆盖率门禁补全 — common 此前无 verify 规则(静默不算数)。
//
// 口径写清楚，免得下一个人数错再"顺手调低"：
//  - 本规则校验的是 **debug 变体**（`:common:koverVerify` → `koverGenerateArtifactDebug`）。
//  - 2026-11-04 实测：debug 变体 LINE 347/485 = **71.5%**，所以 65 是真在咬人的门槛。
//  - 聚合的 `build/reports/kover/report.xml` 会把**没有测试数据的 release 变体**一起算进来
//    （那里 LINE 只有 23.3%），拿它对照 65 会误判成"门禁无效"——我自己就差点这么写进去。
//  - 原来这里的注释写着"按当前实测 LINE 22.6% 设定"，与下面的 `minBound(65)` 互相矛盾；
//    那种注释会鼓励别人把门槛调回去。
kover {
    reports {
        verify {
            rule {
                minBound(65)
            }
        }
    }
}
