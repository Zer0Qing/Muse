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

    // P4-1: ktlint 假绿修复。
    // 本仓库使用 AGP 9 内置 Kotlin 支持(模块不再应用 org.jetbrains.kotlin.android),
    // ktlint-gradle 12.1.1 靠 withId("org.jetbrains.kotlin.android") 挂钩 Android 源集,
    // 该钩子永不触发 → ktlintCheck 只查 .kts,对 .kt 零动作(门禁等于没有)。
    // 这里自建 ktlintKotlinSourceCheck: 用 ktlint 官方 CLI 真扫本模块 Kotlin 源,
    // 首跑自动生成 ktlint-baseline.xml(已入库),之后新增问题即失败;
    // 同时断言源文件非空,防止门禁再次退化为空跑。
    pluginManager.withPlugin("org.jlleitschuh.gradle.ktlint") {
        val ktlintKotlinSourceCheckTask = tasks.register<org.gradle.api.tasks.JavaExec>("ktlintKotlinSourceCheck") {
            group = "verification"
            description = "P4-1: 对 Kotlin 源跑真实 ktlint CLI(替代 AGP9 下失效的 ktlintCheck .kt 检查)"
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
        if (tasks.findByName("check") != null) {
            tasks.named("check") { dependsOn(ktlintKotlinSourceCheckTask) }
        }
    }
}
