# Muse 贡献指南

## 欢迎贡献！

感谢您对 Muse 的关注！无论是报告 Bug、提交功能建议、改进文档还是提交代码，我们都欢迎。

## 行为准则

本项目采用贡献者契约行为准则。请保持尊重、包容的沟通氛围。

## 如何贡献

### 报告 Bug
1. 在 Issues 搜索是否已有相同报告
2. 若无，创建新 Issue 并附上：
   - 设备型号与 Android 版本
   - 复现步骤（期望行为 vs 实际行为）
   - 日志截图或 adb logcat 输出

### 提交功能建议
1. 先搜索 Discussions 确认无人提过
2. 在 Issue 中清晰描述使用场景和期望结果

### 提交 Pull Request

1. Fork 本仓库并创建您的分支
2. 遵循现有代码风格（Kotlin 官方风格）
3. 新功能请包含单元测试
4. 确保 `./gradlew ktlintCheck` 通过
5. 确保 `./gradlew :app:testDebugUnitTest` 全部通过
6. 更新相关文档（docs/ 目录,若仓库有对应文档）
7. 提交 PR 到 main 分支

### 本地开发环境
- Android Studio Ladybug (2024.2+) 或更高
- JDK 21
- Android SDK 35
- Gradle 9.4.1 (wrapper 已包含)

### 测试
- 纯 JVM 测试：`./gradlew :app:testDebugUnitTest`
- Lint 检查：`./gradlew ktlintCheck`
- 构建 Debug APK：`./gradlew assembleDebug`
- 构建 Release APK：`./gradlew assembleRelease`

## 代码规范

- 遵循 docs/ENGINEERING_DISCIPLINE.md(若文档未入库则以 AGENTS.md 为准)
- 提交信息使用中文或英文，保持清晰

## 文档

所有文档位于 docs/ 目录。修改代码后请同步更新对应的 .md 文件。

## 许可证

通过贡献代码，您同意您的贡献将在 GPL v3 许可下授权。
