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
    }
}
