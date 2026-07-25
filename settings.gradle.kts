pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "muse"

// 模块清单(逐模块从零实现)
// Phase 1: 仅启用对话闭环所需模块,其余在 Phase 5 按需开启
include(":app")          // 主应用模块(UI/ViewModel/核心逻辑)
include(":ai")           // AI SDK 抽象层(Provider/Model/Tool/UIMessage)
include(":memory")       // 记忆系统
include(":common")       // 通用工具与扩展
// v1.97 gap7: 启用 :material3 模块 — 挂载 material-color-utilities 源码,
// 提供 DynamicScheme.toColorScheme() 扩展,供 CustomTheme 基于种子色生成 ColorScheme
include(":material3")    // Material 颜色扩展(种子色 → ColorScheme 生成器)
// include(":search")       // 搜索功能 SDK(Exa/Tavily/Zhipu/Bing 等)        — Phase 5
// include(":speech")       // 语音 TTS/ASR                                   — Phase 5
// include(":document")     // 文档解析(PDF/DOCX/PPTX/EPUB)                  — Phase 5
// include(":highlight")    // 代码语法高亮                                   — Phase 5
// include(":workspace")    // proot Linux 沙盒环境                           — Phase 5
// include(":web")          // 嵌入式 Web 服务器                              — Phase 5
