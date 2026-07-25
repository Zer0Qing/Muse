plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
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
}

// v1.78 (H4): 导出 Room schema JSON,为未来编写 Migration 提供基线
// 替代 fallbackToDestructiveMigration 的"版本升级即丢数据"行为
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
}
