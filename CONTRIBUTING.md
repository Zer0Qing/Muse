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
4. 确保 `./ci/run_ci_checks.ps1 -Lane static` 通过（detekt、ktlint、lint 和覆盖率门禁）
5. 确保 `./ci/run_ci_checks.ps1 -Lane unit` 全部通过
6. 更新相关文档（README.md / release_notes.md 等根目录显性文档）
7. 提交 PR 到 main 分支

### 本地开发环境
- Android Studio Ladybug (2024.2+) 或更高
- JDK 21
- Android SDK 35
- Gradle 9.4.1 (wrapper 已包含)

### 测试
- CI 脚本与工程规则：`./ci/run_ci_checks.ps1 -Lane ci-scripts` 与 `./ci/run_ci_checks.ps1 -Lane lanes`
- 静态检查：`./ci/run_ci_checks.ps1 -Lane static`
- 模块单元测试：`./ci/run_ci_checks.ps1 -Lane unit`
- 构建 Debug APK：`./ci/run_ci_checks.ps1 -Lane debug`
- 构建 Release APK：需显式提供 release 签名和版本参数，见 `README.md` 与 CI 的 tag 流程

> **同一个工作区并行跑构建的坑**：IDE 正在构建、命令行又跑测试，或多人/多 Agent 并行验证时，后启动的构建会清空 `app/build/test-results/testDebugUnitTest/`，表现为 `java.nio.file.NoSuchFileException: …/binary/in-progress-results-*.bin`，并且该目录只剩上一次的旧 XML。这类失败既不算通过也不算失败——测试根本没跑完，不要当成测试挂了去改代码。
>
> 判定方法：看结果 XML 的时间戳是否属于本次运行，并核对类数；用 `--tests "<包>.*"` 过滤跑时，结果目录里应当是完整的类集合，被清空的目录给不出完整结果集。确认没有其它构建在跑后重跑即可。

> **`compileDebugKotlin` 误报 `UP-TO-DATE` 的坑（Windows 实测）**：外部进程（编辑器、脚本、另一个 Agent）改写源码后，Gradle 守护进程的 VFS 可能感知不到变更，于是 `:app:compileDebugKotlin` 直接报 `UP-TO-DATE` 并「成功」——这时「编译通过」是假阳性，改动根本没进编译器。已确认 `--stop` 重启守护、`--no-watch-fs` 都不能稳定规避（两者都复现过），也不是偶发。
>
> 判定方法：看 `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/` 下对应 class 的时间戳是否晚于最后一次改源码。需要确认「真的编译过」时用 `--rerun`（只强制重跑该任务，依赖仍走增量，代价小）；要排除一切缓存影响再用 `--rerun-tasks`（全量重跑，明显更慢）。

> **PowerShell 会吃掉 `--tests` 的通配符**：在 pwsh 里写 `--tests "*Settings*"`，通配符会被当作路径展开成目录里的 `settings.gradle.kts`，Gradle 收到的是一个不存在的测试过滤器，报 `No tests found for given includes: [settings.gradle.kts]`。这个失败跟测试本身毫无关系，改代码是白改。
>
> 规避：改用全限定类名（`--tests "io.zer0.muse.data.SettingsRepositoryTest"`）或包路径（`--tests "io.zer0.muse.data.*"`）；必须用通配符时让命令走 `cmd` 而不是 pwsh。同理，跑完先看结果目录里的 XML 类数，别只看 `BUILD SUCCESSFUL`。

> **`kspDebugUnitTestKotlin` 报 `[MissingType]: Element '…' references a type that is not present` 的坑**：根因通常不是代码，而是 `app/build/intermediates/compile_app_classes_jar/…/bundleDebugClassesToCompileJar/classes.jar`（以及同源的 `runtime_app_classes_jar/…/classes.jar`）被写成了残缺版——只剩 R、AIDL、BuildConfig，几十条到几百条，主源码的 Kotlin class 一个都没有。更麻烦的是它会被 Gradle 判定为 `UP-TO-DATE`，所以清 `kspCaches`、清 `generated/ksp` 全都无效。并发构建下两个进程同时清空并重写 `built_in_kotlinc/…/classes` 时最容易踩到。

> 判定方法：`bundleDebugClassesToCompileJar/classes.jar` 体积只有 1MB 左右、条目数几百且不含 `io/zer0/muse/` 业务类，即为损坏；对照 `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes` 下的 class 数是全的。

> 修复：删掉 `app/build/intermediates/compile_app_classes_jar` 与 `app/build/intermediates/runtime_app_classes_jar` 两个目录，重跑即可，不要动源码。同源症状还有测试阶段抛 `NoClassDefFoundError`（主源码类从同样残缺的 runtime jar 里取不到），修法相同。

## 代码规范

- 遵循仓库根目录的 AGENTS.md(工程纪律与协作约定以该文件为准)
- 提交信息使用中文或英文，保持清晰

## 文档

对外文档位于仓库根目录（README.md / README_EN.md / release_notes.md / 用户手册.md 等）。修改代码后请同步更新对应的 .md 文件；内部设计文档不随仓库维护。

## 许可证

通过贡献代码，您同意您的贡献将在 GPL v3 许可下授权。
