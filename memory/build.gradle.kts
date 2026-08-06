plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    // Phase 2.1: Kover — 插桩本模块字节码,数据上提到 root 聚合报告
    alias(libs.plugins.kover)
}

android {
    namespace = "io.zer0.memory"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Phase 2.2: Robolectric 需要 merged android resources(读取 AndroidManifest + assets)
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // Phase 2.2 (修复): 让 MigrationTestHelper 在 unit test 中能从 assets 读取 schema JSON。
    // 默认 schemaLocation 只导出到 $projectDir/schemas,但 MigrationTestHelper 从 assets 加载。
    // 把 schemas 目录注册到 test assets,Robolectric 会合并到 merged assets 供测试读取。
    sourceSets {
        getByName("test").assets.srcDirs("$projectDir/schemas")
    }
}

// v1.78 (H4): 导出 Room schema JSON,为未来编写 Migration 提供基线
// 替代 fallbackToDestructiveMigration 的"版本升级即丢数据"行为
// Phase 2.2: 该路径同时被 MigrationTestHelper 读取以验证 v3→v8 各阶段迁移
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":ai"))

    // Room + FTS4
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Serialization & Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // DataStore(用于 daily-state 等断点续跑状态)
    implementation(libs.androidx.datastore.preferences)

    // Koin DSL(memory 模块要 module/single)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // v1.55: 真实 tokenizer(BPE 编码,LlmBudget 软裁剪用)
    implementation(libs.jtokkit)

    // 测试
    testImplementation(libs.junit)
    // Phase 2.2: Room Migration 测试基础设施 — 与 app 模块版本对齐(room=2.8.4)
    testImplementation("androidx.room:room-testing:2.8.4")
    // Robolectric: JVM 上跑 Android Context(SQLite/Cursor),无需真机
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(libs.kotlinx.coroutines.test)
}
kover {
    reports {
        verify {
            rule {
                minBound(30)
            }
        }
    }
}
