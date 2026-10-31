plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "io.zer0.muse.miniphone"
    compileSdk = 36

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
        // 迁移测试要按导出的 schema 造一个旧版本的库:把 schemas 目录当测试资产,
        // 测试里直接读 1.json 建表,不把建表语句抄一份到代码里
        getByName("test").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        unitTests {
            // Robolectric 需要 merged resources 才能拿到 Application Context(Room 内存库用)
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
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

// 本模块 DB 独立于宿主 MuseDb,版本号从 1 起,导出 schema 作为后续迁移的基线。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AI 消息与工具定义的公共类型:角色的回复走宿主注入的生成能力,但会话形状由本模块定义
    implementation(project(":ai"))

    // 日志门面(Logger):后台定时生成必须能从 logcat 看出「为什么没发」
    implementation(project(":common"))

    // Compose(桌面外壳与页面)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    // 布局/Canvas/Lazy 列表都在 foundation;material3 只把它当 implementation 依赖,不传递到编译期
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.compose.material3)

    // ViewModel(外壳状态的唯一持有者)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 协程(Flow / suspend 接口)
    implementation(libs.kotlinx.coroutines.android)

    // Koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // 系统返回键(聊天页退出)
    implementation(libs.androidx.activity.compose)

    // 头像/图片加载:版本跟宿主一致(coil = 2.7.0),不另引新版本
    implementation(libs.coil.compose)

    // 后台定时生成动态:WorkManager 版本跟宿主一致(toml 的 work = 2.9.0),
    // 不另引新版本 —— 宿主 :app 已经在跑同一个运行时,两处版本必须同源
    implementation(libs.androidx.work.runtime.ktx)

    // 模块内设置(自动发朋友圈开关、上次生成时间):不往 mp_profile 加字段,也不动 Room schema
    implementation(libs.androidx.datastore.preferences)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.kotlinx.coroutines.test)
    // Compose UI 测试(Robolectric 本地 JVM):tab 切换、列表顺序、空态都靠它验证
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
