import org.gradle.api.tasks.testing.Test
import java.io.FileInputStream
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    // Phase 2.1: Kover — 插桩本模块字节码,数据上提到 root 聚合报告
    alias(libs.plugins.kover)
}

android {

    namespace = "io.zer0.muse"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.zer0.muse"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 35
        // v1.0.27 P0-1.1: 版本号支持从 Gradle property 注入,CI 从 git tag 自动提取
        // 优先级: -PversionCode/-PversionName > 环境变量 > 默认值
        // 本地构建用默认值,CI 通过 ./gradlew assembleRelease -PversionName=1.0.83 注入
        // 空字符串视为未注入(workflow_dispatch 无 tag 时回退默认值)
        // v1.0.83: 稳定性修复正式基线(正式构建仍由 CI 显式注入)
        versionCode = (project.findProperty("versionCode") as? String)
            ?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()
            ?: System.getenv("VERSION_CODE")?.takeIf { it.isNotBlank() }?.toIntOrNull()
            ?: 183
        versionName = (project.findProperty("versionName") as? String)
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }
            ?: "1.0.83"
    }

    signingConfigs {
        create("release") {
            // v1.89: 支持通过 keystore.properties 指定独立 release 签名(安全改进 H-1)
            // 正式发布时在项目根目录创建 keystore.properties 文件,内容:
            //   storeFile=路径
            //   storePassword=密码
            //   keyAlias=别名
            //   keyPassword=密码
            // 未提供时回退到 debug keystore(仅供开发调试)
            if (keystorePropertiesFile.exists()) {
                val props = Properties()
                props.load(FileInputStream(keystorePropertiesFile))
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            } else {
                // 回退: debug keystore(仅开发调试,正式发布须创建 keystore.properties)
                storeFile = signingConfigs.getByName("debug").storeFile
                storePassword = signingConfigs.getByName("debug").storePassword
                keyAlias = signingConfigs.getByName("debug").keyAlias
                keyPassword = signingConfigs.getByName("debug").keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions {
        unitTests {
            // 未 mock 的 android.* 方法返回默认值,避免 Logger/Log 调用在 JVM 单元测试中崩溃
            isReturnDefaultValues = true
            // 让 Robolectric 测试可以读取合并后的 Android 资源,避免 Resources$NotFoundException
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // P3-3: 启用 AIDL — IShellService(Shizuku UserService 接口)需要生成 Stub/Proxy
        aidl = true
    }

    // v1.89: packaging 配置 — 排除重复的 META-INF 文件,避免构建冲突
    // Phase 6 6A: APK 体积优化 — 按 ABI 分包(arm64-v8a / armeabi-v7a),减少单 APK 体积
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            // 日常验证用 -PnoUniversal 跳过 169MB 的 universal 包，只打 ABI 分包
            isUniversalApk = providers.gradleProperty("noUniversal").isPresent.not()
        }
    }

    lint {
        abortOnError = true
        baseline = file("lint-baseline.xml")
        // Suppress NewApi warning for displayCutoutMode (minSdk 26, feature is API 27+)
        // but the theme is only applied on API 27+ devices via values-v27
        warning.add("NewApi")
        // Suppress MissingPermission for BluetoothAdapter.disable (runtime check in place)
        warning.add("MissingPermission")
        // Suppress unused resource warnings (many resources from auto-generated code)
        warning.add("UnusedResources")
        // i18n: 未翻译的字符串视为 error,拦截漏翻
        error.add("MissingTranslation")
    }

    packaging {
        resources {
            excludes +=
                listOf(
                    "META-INF/AL2.0",
                    "META-INF/LGPL2.1",
                    "META-INF/DEPENDENCIES",
                    "META-INF/LICENSE*",
                    "META-INF/NOTICE*",
                    "META-INF/*.kotlin_module",
                )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// 发布全量测试包含大量 Robolectric/Compose 用例;定期重启测试 worker
// 防止单个 JVM 长时间累积 native/Compose 资源后在尾部用例 OOM。
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    forkEvery = 200
}

dependencies {
    // 项目内模块
    implementation(project(":ai"))
    implementation(project(":memory"))
    implementation(project(":common"))
    // v1.97 gap7: :material3 模块 — DynamicScheme.toColorScheme() 扩展,
    // 供 CustomTheme 基于种子色生成完整 ColorScheme
    implementation(project(":material3"))
    // P3-3: 无障碍服务模块 — MuseAccessibilityService + IAccessibilityProvider AIDL
    implementation(project(":accessibility"))

    // P3-3: Shizuku SDK — 以 shell 权限执行命令(三通道路由之一,无需 root)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    // v1.7: 系统 SplashScreen API(androidx.core:core-splashscreen)
    implementation(libs.androidx.core.splashscreen)
    // v1.60-C: AppCompat(per-app 语言切换,支持 Android 13 以下系统)
    implementation(libs.androidx.appcompat)
    // 功能1: 生物识别解锁
    implementation(libs.androidx.biometric)
    // v1.104 P3: WorkManager — ScheduledTaskRunner 后台兜底,App 被杀也能由系统拉起
    implementation(libs.androidx.work.runtime.ktx)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Material3 — BOM 2026.06.01 已升级;MaterialExpressiveTheme 在 1.4.0 stable 仍为 internal,
    // 故显式保留 1.4.0-alpha04 直到 stable 公开该 API(见 AUDIT_PROGRESS R-BUILD-02 阻塞记录)
    // R-BUILD-02 阻塞:material3 1.4.0 stable 的 MaterialExpressiveTheme/MotionScheme 为 internal,
    // 暂维持 1.4.0-alpha04 与 Compose BOM 2024.12.01,避免主题回归(见 AUDIT_PROGRESS)。
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Tabler Icons Compose(线条图标库,补充 Material Icons)
    implementation(libs.composeIcons.tablerIcons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Glance Compose 桌面小部件(Phase 12)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // v1.0.72: test 源码集的 @Database 类(如 GroupChatMemoryRepositoryTest 的 TestMuseDb)
    // 需要 KSP 生成 _Impl,否则 Robolectric 单元测试报 ClassNotFoundException
    kspTest(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif) // Phase 11.1.6: GIF 动图解码
    // v1.0.72: CameraX — 加号菜单"拍照预览"(Telegram 风格实时取景)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)
    // v1.94: Jsoup — HTML 解析(搜索结果 + web_fetch 正文提取,替代 regex + Html.fromHtml)
    implementation(libs.jsoup)

    // Serialization & Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Phase 8.6: PDF 文本提取(pdfbox-android,Apache 2.0)
    implementation(libs.tom.roush.pdfbox.android)
    // Phase 8.6: ML Kit 文字识别(中英文离线 OCR)
    implementation(libs.google.mlkit.text.recognition.chinese)

    // v1.134 P0-1: ONNX Runtime — 本地 embedding / cross-encoder rerank 推理。
    // 用户已确认引入(onnxruntime-android 1.23.0,APK 体积增加 ~40MB)。
    // 配合 OnnxEmbeddingProvider / OnnxRerankProvider 使用,
    // 模型文件不内置 APK(避免体积膨胀),由用户从设置页导入到 filesDir/muse_onnx/。
    // 不可用时自动降级到 LocalKeywordEmbeddingProvider / LocalRerankProvider。
    implementation(libs.onnxruntime.android)

    // v1.49: 移除 Vosk 离线语音识别(com.alphacephei:vosk-android:0.3.47)
    // 原因:vosk-android native lib 每 ABI 约 8-9MB,4 个 ABI 共 34MB,占 APK 体积过大。
    // 改为:默认走云端 ASR(DashScope/Step),无 API Key 时回退系统 Intent(SpeechInput)。

    // Phase 8.11: Ktor 嵌入式 Web 服务器(CIO 引擎 + JWT + ContentNegotiation + CORS)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.auth)
    // 排除 jwks-rsa: 它依赖 guava,与 AndroidX 的 listenablefuture 能力冲突;
    // 我们用 HMAC-SHA256 对称签名,不需要 JWKS(RSA 公钥轮换),排除不影响功能
    implementation(libs.ktor.server.auth.jwt) {
        exclude(group = "com.auth0", module = "jwks-rsa")
    }
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.auth0.java.jwt)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // v1.89: 测试基础设施补强 — 供后续 Mock/Flow 测试和仪器化测试使用
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    // Compose UI 测试（Robolectric 本地 JVM）
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)

    // LeakCanary 内存泄漏检测(仅 debug)
    debugImplementation(libs.leakcanary.android)
    // v1.55: 真实 tokenizer(BPE 编码,cl100k_base — GPT-4/3.5 通用,其他模型近似)
    implementation(libs.jtokkit)

    // v1.97: 二维码生成与扫描(zxing 生成 + ML Kit barcode 扫描图片)
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode.scanning)
}

// 发布安全：正式构建必须使用独立 keystore.properties，禁止静默回退 debug 签名。
// CI 只做 debug 构建+静态检查(会触发部分 Release 任务),用 -PreleaseSkipKeystoreCheck=true 跳过。
gradle.taskGraph.whenReady {
    val hasReleaseTask = allTasks.any { it.name.contains("Release") }
    val skipKeystoreCheck = project.findProperty("releaseSkipKeystoreCheck") == "true"
    if (hasReleaseTask && !skipKeystoreCheck && !keystorePropertiesFile.exists()) {
        throw GradleException("正式构建缺少 keystore.properties：请先配置 release 签名，禁止回退 debug 签名。")
    }
    // 版本号硬约束：正式构建必须显式注入 versionName/versionCode，避免误用过期默认版本；当前默认线为 183/1.0.83。
    // 本地临时验证可传 -PreleaseSkipVersionCheck=true 跳过。
    val skipVersionCheck = project.findProperty("releaseSkipVersionCheck") == "true"
    val hasVersionName = project.hasProperty("versionName") || !System.getenv("VERSION_NAME").isNullOrBlank()
    val hasVersionCode = project.hasProperty("versionCode") || !System.getenv("VERSION_CODE").isNullOrBlank()
    if (hasReleaseTask && !skipVersionCheck && (!hasVersionName || !hasVersionCode)) {
        throw GradleException("正式构建必须注入版本号：请传 -PversionName/-PversionCode 或设置 VERSION_NAME/VERSION_CODE。")
    }
}
kover {
    reports {
        verify {
            rule {
                minBound(12)
            }
        }
    }
}
// 审查修复 (2.0 B-31): 上方 verify 规则无变体作用域,对 koverVerify 的全部变体
// (Debug/Release 等)生效 — 文档 AUDIT_PROGRESS.md 原称"debug-only"与事实不符,
// 措辞已修正;若未来需要真正 debug-only,需在此按 Kover 变体 API 限定。
