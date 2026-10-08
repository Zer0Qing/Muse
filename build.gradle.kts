// 顶层 build.gradle.kts
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt)
    // Phase 2.1: Kover 顶层应用,作为 merging module 汇总 6 模块覆盖率
    // 各子模块仍需各自 alias(libs.plugins.kover) 才会插桩本模块字节码
    alias(libs.plugins.kover)
}

// Phase 2.1: 聚合 6 模块的覆盖率数据到 root,运行 :koverXmlReport / :koverHtmlReport 即可生成全量报告
dependencies {
    kover(project(":app"))
    kover(project(":ai"))
    kover(project(":memory"))
    kover(project(":common"))
    kover(project(":material3"))
    kover(project(":accessibility"))
}

// Phase 2.1: 配置聚合报告格式 — HTML + XML(使用默认输出路径)

// R-CI-02: detekt 接入,统一使用仓库根目录配置;子模块逐项目分析
detekt {
    config.setFrom(rootProject.files("detekt.yml"))
    buildUponDefaultConfig = true
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("detekt.yml"))
        buildUponDefaultConfig = true
        val baselineFile = project.file("detekt-baseline.xml")
        if (baselineFile.exists()) {
            baseline = baselineFile
        }
    }

    // Compose 升级(2026.02.01 / Compose 1.10)适配:
    // material3 是独立版本线,不跟 Compose BOM。BOM 2026.02.01 的 platform 约束会把
    // material3 及其 material3-android 顶到 1.4.0;而 1.4.0 正式版已把
    // MaterialExpressiveTheme/MotionScheme 移出到 1.5.0-alpha,项目主题依赖 expressive API。
    // 因此全模块强制将 material3 锁回 1.4.0-alpha04。
    // 用 force 而非 strictly:strictly 会和 BOM 约束硬冲突导致解析直接报错。
    // 注:subprojects 作用域内拿不到 libs 版本目录扩展,版本号与 libs.versions.toml 的
    // material3 保持一致(升级该版本时需同步这里)。
    val material3Version = "1.4.0-alpha04"
    configurations.configureEach {
        resolutionStrategy.force(
            "androidx.compose.material3:material3:$material3Version",
            "androidx.compose.material3:material3-android:$material3Version",
        )
    }

    // P4-1: ktlint 假绿修复。
    // 本仓库使用 AGP 9 内置 Kotlin 支持(模块不再应用 org.jetbrains.kotlin.android),
    // ktlint-gradle 12.1.1 靠 withId("org.jetbrains.kotlin.android") 挂钩 Android 源集,
    // 该钩子永不触发 → ktlintCheck 只查 .kts,对 .kt 零动作(门禁等于没有)。
    // 这里自建 ktlintKotlinSourceCheck: 用 ktlint 官方 CLI 真扫本模块 Kotlin 源,
    // 首跑自动生成 ktlint-baseline.xml(已入库),之后新增问题即失败;
    // 同时断言源文件非空,防止门禁再次退化为空跑。
    pluginManager.withPlugin("org.jlleitschuh.gradle.ktlint") {
        val ktlintConfiguration =
            configurations.findByName("ktlint")
                ?: throw GradleException("ktlint configuration missing in project '$name' (ktlint plugin applied?)")
        // ktlint 配置本身不带消费属性,KMP 依赖会解析到 -sources 变体(运行时缺 KLogger 等)。
        // 自建 RUNTIME+LIBRARY 可解析配置,extendsFrom ktlint,拿到正确的二进制变体。
        val ktlintCliClasspath =
            configurations.create("ktlintCliClasspath_${this.name}") {
                isCanBeConsumed = false
                isCanBeResolved = true
                extendsFrom(ktlintConfiguration)
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                    attribute(
                        Category.CATEGORY_ATTRIBUTE,
                        objects.named(Category::class.java, Category.LIBRARY),
                    )
                }
            }
        val ktlintKotlinSourceCheckTask = tasks.register<org.gradle.api.tasks.JavaExec>("ktlintKotlinSourceCheck") {
            group = "verification"
            description = "P4-1: 对 Kotlin 源跑真实 ktlint CLI(替代 AGP9 下失效的 ktlintCheck .kt 检查)"
            classpath = ktlintCliClasspath
            mainClass.set("com.pinterest.ktlint.Main")

            val srcRoot = layout.projectDirectory.dir("src").asFile.invariantSeparatorsPath
            val reportFile = layout.buildDirectory.file("reports/ktlint/ktlintKotlinSourceCheck.txt")
            val checkstyleFile = layout.buildDirectory.file("reports/ktlint/ktlintKotlinSourceCheck.xml")
            val baselineFile =
                layout.projectDirectory.file("ktlint-baseline.xml").asFile.invariantSeparatorsPath
            inputs.files(layout.projectDirectory.dir("src").asFileTree.matching { include("**/*.kt") })
            outputs.files(reportFile, checkstyleFile)

            doFirst {
                check(!inputs.files.isEmpty) {
                    "ktlintKotlinSourceCheck would lint zero .kt files under $srcRoot — refusing to run an empty gate"
                }
                args(
                    "--relative",
                    "--baseline=$baselineFile",
                    "--reporter=plain,output=${reportFile.get().asFile.invariantSeparatorsPath}",
                    "--reporter=checkstyle,output=${checkstyleFile.get().asFile.invariantSeparatorsPath}",
                    "src",
                )
            }
            doLast {
                // 报告非空断言依据: 追加实际落 lint 的 .kt 文件数(来自任务输入源集,非占位)。
                val linted = inputs.files.files.size
                reportFile.get().asFile.appendText("\n@linted-file-count=$linted\n")
            }
        }
        // P4-1b: 与 ktlintKotlinSourceCheck 镜像的格式化任务 —— 同一份 CLI/classpath,真跑 ktlint -F。
        // 为什么必须有它: check 任务带 --baseline,而 baseline 命中的存量问题在 -F 下不会被修,
        // 所以清理存量格式化债必须有一份"不带 baseline"的入口;此前只能手工拼 java -cp,不可复现。
        // 用法:
        //   ./gradlew :app:ktlintKotlinSourceFormat                      # 全模块
        //   ./gradlew :app:ktlintKotlinSourceFormat "-PktlintPaths=src/main/java/io/zer0/muse/MainActivity.kt,src/test"  # 限定路径
        // 注意: 格式化后必须重建该模块 ktlint-baseline.xml(见 PROJECT_GUIDE §6)。
        // 取值必须在配置期完成 —— 本仓库开了 configuration cache,执行期碰 project 会直接构建失败。
        val formatPaths =
            (project.findProperty("ktlintPaths") as? String)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf("src")
        val missingFormatPaths = formatPaths.filter { !project.file(it).exists() }
        // 配置期就把 Project 相关引用拍成纯字符串 —— 执行期(lambdas)持有 Project 会撑爆 configuration cache。
        val formatProjectDir = layout.projectDirectory.asFile.invariantSeparatorsPath
        tasks.register<org.gradle.api.tasks.JavaExec>("ktlintKotlinSourceFormat") {
            group = "verification"
            description = "P4-1b: 用 ktlint CLI 就地格式化本模块 Kotlin 源(不带 baseline,可清理存量债)"
            classpath = ktlintCliClasspath
            mainClass.set("com.pinterest.ktlint.Main")
            // 格式化会就地改源文件,不参与增量判定(每次显式执行都真跑)。
            outputs.upToDateWhen { false }
            // ktlint -F 对"改不了"的违规(超长行/注释位置/文件名大小写)返回 1 —— 那是待办清单,
            // 不是任务失败。门禁归 ktlintKotlinSourceCheck:它带 baseline,新问题一律红。
            isIgnoreExitValue = true

            doFirst {
                check(missingFormatPaths.isEmpty()) {
                    "ktlintKotlinSourceFormat: path(s) not found under $formatProjectDir: $missingFormatPaths"
                }
                args("--relative", "-F", *formatPaths.toTypedArray())
            }
        }
        if (tasks.findByName("check") != null) {
            tasks.named("check") { dependsOn(ktlintKotlinSourceCheckTask) }
        }
    }
}
