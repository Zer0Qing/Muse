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

val museWebAssetsDir = layout.buildDirectory.dir("generated/museWebAssets").get().asFile
val syncMuseWebAssets =
    tasks.register<Copy>("syncMuseWebAssets") {
        val webDist = rootProject.file("web/apps/client/dist")
        from(webDist)
        into(File(museWebAssetsDir, "muse-web"))
        onlyIf { webDist.isDirectory }
    }

// v2.2.1: 无障碍独立 Provider APK 随包资产 — 按变体各取各的签名产物
// (debug 主应用 ↔ debug Provider;release 主应用 ↔ release Provider,同一把 keystore)。
// 签名级权限 A11Y_BRIDGE 要求主应用与 Provider 同签名,故 release 不允许回退 debug 变体。
abstract class SyncA11yProviderApkTask : DefaultTask() {
    @get:InputFile
    abstract val sourceApk: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val targetDir = outputDir.get().asFile.resolve("a11y").apply { mkdirs() }
        sourceApk.get().asFile.copyTo(targetDir.resolve("accessibility-provider.apk"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val isRelease = variant.buildType == "release"
        val providerApk =
            rootProject.file(
                if (isRelease) {
                    "accessibility-provider/build/outputs/apk/release/accessibility-provider-release.apk"
                } else {
                    "accessibility-provider/build/outputs/apk/debug/accessibility-provider-debug.apk"
                },
            )
        val syncTask =
            tasks.register<SyncA11yProviderApkTask>(
                "syncA11yProviderApk" + variant.name.replaceFirstChar { it.uppercase() },
            ) {
                dependsOn(
                    if (isRelease) {
                        ":accessibility-provider:assembleRelease"
                    } else {
                        ":accessibility-provider:assembleDebug"
                    },
                )
                doFirst {
                    check(providerApk.isFile) {
                        "未找到 Provider APK:${providerApk.path}" +
                            "(release 需要 keystore.properties 先产出已签名的 Provider)"
                    }
                }
                sourceApk.set(providerApk)
            }
        variant.sources.assets?.addGeneratedSourceDirectory(syncTask, SyncA11yProviderApkTask::outputDir)
    }
}

// v2.2.1: 虚拟屏服务端 jar 打进 assets(首次使用时由 shell 身份写到 /data/local/tmp)。
// 注:server 从不安装、不参与签名校验,debug/release 主应用共用 debug 变体产物即可。
val vdServerAssetsDir = layout.buildDirectory.dir("generated/vdServer").get().asFile
val syncVdServerJar =
    tasks.register<Copy>("syncVdServerJar") {
        dependsOn(":virtual-display-server:assembleDebug")
        val serverApk =
            rootProject.file("virtual-display-server/build/outputs/apk/debug/virtual-display-server-debug.apk")
        from(serverApk)
        into(File(vdServerAssetsDir, "vd"))
        rename { "vd-server.jar" }
        onlyIf { serverApk.isFile }
    }

tasks.named("preBuild") {
    dependsOn(syncMuseWebAssets)
    dependsOn(syncVdServerJar)
}

android {

    sourceSets.getByName("main").assets.srcDir(museWebAssetsDir)
    sourceSets.getByName("main").assets.srcDir(vdServerAssetsDir)

    namespace = "io.zer0.muse"
    compileSdk = 36

    // v2.2.x: 终端 PTY 原生库(自写 forkpty,零第三方代码;源码 app/src/main/jni/)
    // ⚠️ 接入后所有构建均需 NDK 27.0.12077973 + CMake 3.22.1(见 CI 工作流)
    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "io.zer0.muse"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 36
        // v1.0.27 P0-1.1: 版本号支持从 Gradle property 注入,CI 从 git tag 自动提取
        // 优先级: -PversionCode/-PversionName > 环境变量 > 默认值
        // 本地构建用默认值,CI 通过 ./gradlew assembleRelease -PversionName=1.0.87 注入
        // 空字符串视为未注入(workflow_dispatch 无 tag 时回退默认值)
        // v1.0.90: 全量整改收官(插件市场/UI与可访问性/安全与可靠性/CI 护栏)(正式构建仍由 CI 显式注入)
        // v1.0.91: 补丁版 —— 修记忆页置顶区崩溃、修自定义供应商页页签撑满整屏
        // v2.0.0: 渠道桥(飞书/QQ/webhook 接收)/交互式卡片/网页操作/插件UI面板/
        //         OAuth 连接器/技能包/消息批注/通知桥授权/子代理浮窗。
        // v2.1.0: 渠道体系(微信/QQ/飞书/Telegram/钉钉五端、渠道会话化、媒体消息)/
        //         产物交付体系/模型目录工具能力修复/设置搜索浮层/UI 与本地化打磨。
        // v2.2.0: 群聊会议系统/工具瘦身(分层收窄·描述裁剪·find_tools 按需检索)/
        //         助手自管(记忆写入删除、插件卸载启停)/首次引导与首屏重做/图标系统 v1。
        // v2.2.1: 终端(自写 PTY 双引擎·xterm 终端页·Termux 通道)/无障碍独立 Provider APK
        //         (主应用更新不掉线·双路绑定)/虚拟屏 v1(shell 独立服务端·单帧截图·input -d)/
        //         自动化增强(GUI Agent 环·工具失败反馈·命令策略)/主动消息链路与微信扫码修复。
        // v2.3.0: 工具轮次默认无限制/上下文连续性(窗口外历史摘要·工具回合保留助手说明)/
        //         记忆归档缺陷根治(版本守卫·归档保真·一次性恢复)/工具权限分层(标注·预检)/
        //         提示词技能参数·MOOD 调试出口·知识搜索诊断·渠道卡片重设计。
        // v2.4.0: 手机 Agent 工作流/虚拟屏与应用控制/Node npm 沙盒/MCP stdio/
        //         记忆隔离与 RAG 可靠性/语音生命周期/对话恢复一致性。
        // v2.6.0: Codex OAuth、供应商体验改进、可配置快捷操作与 A2A/Tasker 安全边界修复。
        // v2.6.2: 知识库/会话/技能/助手管理工具集、Codex OAuth 修复、中转站请求头注入与主动消息路由修复。
        // v2.6.3: 修设置→聊天必崩（IllegalFormatConversionException）、WebServer 无法启动、工具调用静默失败等 issue #3~#7。
        // v2.6.5: 审计报告全量修复 + 用户反馈（删消息复活/回复被空覆盖/导航条可见性）+ per-KB 配置 + 云端 rerank。
        // v2.6.6: 小手机改造（群聊/相册/朋友圈可见范围/关系表）+ 代理全链路 + 定时任务工具装配。
        // v2.6.7: 设置页按「带着什么问题来」重排为 11 组，把错位选项归位；
        //          修常驻后台等页面的路由未注册；修保活服务缺通知渠道导致的
        //          startForeground 崩溃；关闭 R8 优化以避开超大 Compose 方法的 VerifyError。
        // v2.6.11: 记忆多选合并（预览可编辑、可撤销）+ 记忆状态徽章；
        //          聊天滚动渲染开销（跨 composition 缓存、图片画幅、群聊页每帧重组）。
        // v2.6.12: 小手机整体重写为独立模块（自有美术、朋友圈、语音消息、主动私信、录音输入）；
        //          旧小手机实现与数据表删除（DB 121→122，恢复旧备份时跳过旧小手机会话）；
        //          设置页重排为 9 组；语音对话改手动端点；TTS/ASR 可跟随供应商；
        //          RAG 设置页显示当前实际后端；修记忆孤儿条目、主动消息落点、广播注册崩溃与日志噪声。
        // v2.6.13: 设置页再细分为 14 组（每组 ≤5 项，搜索索引补到 78 条）；小手机对外开放；
        //          小手机可用性（联系人开聊、长按菜单、发送失败重试、会话置顶与清空）；
        //          群聊（建群、最多两人回话、上下文互见）；朋友圈对齐微信（多图左右滑、删评论、
        //          相对时间、点赞名单、封面身份牌）；朋友圈与私信改用角色自己的模型与最近对话。
        // v2.6.14: 网页版：手机开放局域网访问，其他设备扫码进 PWA（开关/地址/PIN/二维码）；
        //          移动优先的 Host 聊天壳（安全区与键盘处理、抽屉、流式光标、停止生成、审批卡、断线重连）；
        //          Web 与 Android 共用一套设计令牌（带漂移检测测试）+ 组件基元；
        //          数据面新增 settings/assistants/providers/sessions 命令；
        //          网页端设置主页（14 组 49 项，文案与图标由 Host 下发）；聊天链路主线程开销优化。
        // v2.6.15: 修 release 包 网页版起不来的问题（R8 删了 Netty 反射创建 Channel 要用的无参构造，
        //          报 Class ... does not have a public non-arg constructor）；
        //          修插件启动时 JsSandbox 未初始化导致激活失败。
        // v2.6.16: 主动消息五档按用户设定改定值：安静 3 / 克制 8 / 标准 15 / 主动 30 / 黏人不限；
        //          废除「等级地板 + 亲密度」拼额度的旧公式（它导致选黏人每天只能发 5 条）；
        //          删除实际不生效的 maxDailyMessages 与 grantCredit 回补额度链路。
        // v2.6.17: Web 服务引擎从 Netty 换成 CIO（Netty 两处反射被 R8 掉，导致 release 包服务端起不来）；
        //          账本里粘人档的额度不再显示 2147483647，改为「今天不限次数」。
        // v2.6.18: 设置改成两级：首页 12 类（48 入口），点进分类看条目、再进子页；搜索带面包屑；
        //          入口清单数据化，首页/分类页/搜索共用一份；
        //          Web 端与 App 的组件几何对齐并纳入校验（40 个几何令牌 + CSS 裸值扫描）；
        //          网页端字体/配色改为与 App 一致；WebServer 只在绑定字段变化时重启（之前改 PIN 也会重启、搞断连接）。
        versionCode = (project.findProperty("versionCode") as? String)
            ?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()
            ?: System.getenv("VERSION_CODE")?.takeIf { it.isNotBlank() }?.toIntOrNull()
            // 兜底值故意压成哨兵：忘带 -PversionCode 时，产物编号一眼就能看出是临时包，
            // 不会再顶着上一个真版本号被当成正式包发出去。取 999999 而不是 0/1，
            // 是为了让 debug 包能盖过任何已装的正式版，不报 INSTALL_FAILED_VERSION_DOWNGRADE。
            ?: 999999
        versionName = (project.findProperty("versionName") as? String)
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }
            // 同上，名字也走哨兵，避免“编号前进一格、名字原地不动”的组合再次出现。
            ?: "0.0.0-dev"
    }

    signingConfigs {
        create("release") {
            // v1.89: 通过 keystore.properties 指定独立 release 签名(安全改进 H-1)。
            // 正式发布时在项目根目录创建 keystore.properties 文件,内容:
            //   storeFile=路径
            //   storePassword=密码
            //   keyAlias=别名
            //   keyPassword=密码
            // 缺失时不再回退到 debug keystore；真正执行 Release 任务时由下方 taskGraph
            // guard 给出明确错误。这样普通 debug/静态检查仍可运行，但不会生成伪装成正式包的 debug 签名 APK。
            if (keystorePropertiesFile.exists()) {
                val props = Properties()
                props.load(FileInputStream(keystorePropertiesFile))
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            // 用 optimize 版默认规则（AGP 9 已不再支持不带 optimize 的那份）。
            //
            // MessageBubble 是单个 2000+ 行、70+ 参数的 Composable，局部寄存器用量
            // 超过 200 个槽。R8 在这个体量上做寄存器级优化会产出类型不一致的 dex，
            // 被 Android 14+ 的 verifier 拒收，表现为点开对话直接 VerifyError。
            // 因此下面用 -dontoptimize 关掉优化（保留混淆与压缩）。
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
            // Robolectric 4.16+ 在 Java 21 下需要开放 JDK 内部模块,
            // 否则 FileDescriptor 探针 / AndroidInterceptors 反射报 IllegalAccessException。
            // 清单取自 Robolectric 官方 Getting Started(Java 17+ 必需)。
            all {
                // 这台机器上 Robolectric 的原生 SQLite 会在 nativeOpen 处触发
                // EXCEPTION_ACCESS_VIOLATION（JVM 直接挂掉，看 hs_err_pid*.log）。
                // 改用纯 Java 的 LEGACY 实现绕开原生库；它同样能执行建表/迁移/FTS 语句。
                it.systemProperty("robolectric.sqliteMode", "LEGACY")
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
            // P0 打样：追加 x86_64（本地模拟器验证内置运行时）；发布前评估是否保留
            include("arm64-v8a", "armeabi-v7a", "x86_64")
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
        // P0 内置运行时：native 库保持解压落盘（extractNativeLibs=true），
        // 使沙盒可从 nativeLibraryDir 直接 exec 内置 Node 运行时（libmuse_node.so）
        jniLibs {
            useLegacyPackaging = true
            // 内置运行时二进制经 patchelf 重写过段布局，且已在 Termux 侧 strip 过；
            // 必须跳过 AGP 的 llvm-strip：对 patchelf 产物再 strip 会破坏段/程序头
            // 对应关系，导致执行时 SIGSEGV（已实测复现）。
            keepDebugSymbols += listOf(
                "**/libmuse_node.so",
                "**/libc++_shared.so",
                "**/libssl.so",
                "**/libcrypto.so",
                "**/libcares.so",
                "**/libicui18n.so",
                "**/libicuuc.so",
                "**/libicudata.so",
                "**/libsqlite3.so",
                "**/libz.so",
                "**/libffi.so",
            )
        }
        resources {
            excludes +=
                listOf(
                    "META-INF/AL2.0",
                    "META-INF/LGPL2.1",
                    "META-INF/DEPENDENCIES",
                    "META-INF/LICENSE*",
                    "META-INF/NOTICE*",
                    "META-INF/*.kotlin_module",
                    // ktor-server-netty 引入多个 Netty 模块, 以下文件在各 jar 中重复
                    "META-INF/INDEX.LIST",
                    "META-INF/io.netty.versions.properties",
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
    // v2.2.1: 虚拟屏契约模块 — IVirtualDisplayService AIDL + 手递手常量
    implementation(project(":virtual-display-protocol"))
    // P0: 小手机模块 — 自带 Room 库(MiniPhoneDb)与宿主接口 MiniPhoneHost。
    // 依赖方向单向 app → miniphone，:miniphone 不反向依赖 :app。
    implementation(project(":miniphone"))

    // P3-3: Shizuku SDK — 以 shell 权限执行命令(三通道路由之一,无需 root)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
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
    // Material3 — 独立版本线,不跟 Compose BOM。
    // 1.4.0 正式版把 MaterialExpressiveTheme/MotionScheme 移入 1.5.0-alpha(官方声明),
    // 而主题依赖 expressive API,因此继续停在 1.4.0-alpha04。
    // 版本锁定统一在 root build.gradle.kts 用 resolutionStrategy.force 处理
    // (strictly 会与 BOM 的 platform 约束硬冲突,改用 force 更宽容)。
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Tabler Icons Compose(线条图标库,补充 Material Icons)
    implementation(libs.composeIcons.tablerIcons)
    // v2.0.1: Haze — Compose backdrop blur(设置页吸顶搜索栏的胶囊内背景模糊;API 31+ 真模糊,低版本降级)
    implementation(libs.haze)
    // v2.6: 液态玻璃专用引擎(Compose 1.10+)。
    implementation(libs.backdrop)
    implementation(libs.liquid)
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
    // DNS/SSRF 专项测试：验证 MockWebServer 实际建立的 socket 地址。
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    // v1.94: Jsoup — HTML 解析(搜索结果 + web_fetch 正文提取,替代 regex + Html.fromHtml)
    implementation(libs.jsoup)

    // Serialization & Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Phase 8.6: PDF 文本提取(pdfbox-android,Apache 2.0)
    implementation(libs.tom.roush.pdfbox.android)
    // Phase 8.6: ML Kit 文字识别(中英文离线 OCR)
    implementation(libs.google.mlkit.text.recognition.chinese)
    implementation(libs.commons.compress)
    implementation(libs.junrar)
    implementation(libs.xz)

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
    // v2.6.17: HTTPS 由 CIO 引擎的 sslConnector 提供。Netty 引擎已移除：
    // 它依赖两处反射（ReflectiveChannelFactory 建 Channel、ResourceLeakDetector 静态初始化按名字找方法），
    // R8 混淆 release 包时会把它们逐处掐掉，表现为服务端启动直接 NoClassDefFoundError。
    implementation(libs.ktor.server.auth)
    // 不引 ktor-server-auth-jwt：它的取凭证/失败路径引用 com.auth0.jwk.JwkException，
    // 而 jwks-rsa 必须排除（拉 guava，与 AndroidX 的 listenablefuture 能力冲突）。
    // 缺类的结果不是 401 而是 NoClassDefFoundError，被 StatusPages 变成 500，
    // /ws 握手也因此失败。我们只用 HMAC-SHA256 对称签名，不需要 JWKS/RSA 轮换，
    // 鉴权改由 ktor-server-auth 的 bearer provider + java-jwt 自己校验（见 HostAuth.kt）。
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.websockets)
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
    // 版本号硬约束：正式构建必须显式注入 versionName/versionCode；当前默认候选线为 266/2.6.6。
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
                minBound(18)
            }
        }
    }
}
// 审查修复 (2.0 B-31): 上方 verify 规则无变体作用域,对 koverVerify 的全部变体
// (Debug/Release 等)生效。若未来需要真正 debug-only,需在此按 Kover 变体 API 限定。
