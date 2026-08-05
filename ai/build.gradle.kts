import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    // Phase 2.1: Kover — 插桩本模块字节码,数据上提到 root 聚合报告
    alias(libs.plugins.kover)
}

android {
    namespace = "io.zer0.ai"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 26

        // v1.0.18: 注入 SiliconFlow 免费模型 fallback key
        // 优先级: -P > 环境变量 > local.properties > PLACEHOLDER
        val freeModelKey = (project.findProperty("FREE_MODEL_KEY") as String?)
            ?: System.getenv("FREE_MODEL_KEY")
            ?: run {
                val lp = rootProject.file("local.properties")
                if (lp.exists()) {
                    val props = Properties()
                    lp.inputStream().use { props.load(it) }
                    props.getProperty("FREE_MODEL_KEY")
                } else null
            }
            ?: "PLACEHOLDER"
        buildConfigField("String", "FREE_MODEL_KEY", "\"$freeModelKey\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":common"))

    // v1.100: Compose runtime 注解(@Immutable),用于标注 UIMessage/ToolCallInfo 为不可变,
    // 让 Compose 编译器跳过无效重组。通过 BOM 管理版本,只引入注解依赖。
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)

    // OkHttp + SSE
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)

    // Serialization & Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Koin(DSL module/single/get)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}
