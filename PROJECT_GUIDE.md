# Muse 项目规范与接手文档

> 面向新接手的开发者（以及未来的自己）。读完这份文档，你应该能：**在本机跑起来 → 理解架构 → 按规范改代码 → 过质量门禁 → 发一个版本**。
>
> 最后更新：2026-09 · 对应版本线 2.1.0

---

## 0. 这是什么

**Muse** 是一个 Android 上的 AI 伴侣应用：对话、记忆、工具调用、多平台渠道接入、任务自动化，全部围绕"一个懂你的 AI 伙伴"这个核心。

- 仓库：`https://github.com/Zer0Qing/Muse`（本地 `E:\1Project\Muse\1muse`）
- 技术栈：Kotlin · Jetpack Compose · Material3（1.4.0-alpha04）· Koin（DI）· Room · KSP · Gradle 多模块
- 配套资产（**仓库外**）：
  | 资产 | 位置 | 说明 |
  |---|---|---|
  | 图标库 | `https://github.com/Zer0Qing/muse-icons`（本地 `E:\1Project\Muse\muse-icons`） | 自绘图标 176 个，MIT；Muse 里所有图标来源于此 |
  | 发布归档 | `E:\1Project\Muse\releases\vX.Y.Z\` | 每版 3 APK + manifest + release_body + release_verification |
- 当前状态：versionName **2.1.0** / versionCode **210** / DB version **100**

---

## 1. 快速开始

### 环境

- JDK 21、Android SDK（platform 35、build-tools 36.0.0）
- 签名：仓库根 `keystore.properties`（**本地持有，不入库**；格式：storeFile/storePassword/keyAlias/keyPassword）
- **命令行统一用 pwsh 7**（Windows PowerShell 5.1 有 UTF-8 编码坑，见 §9）

### 常用命令

```powershell
# 编译（开发期跳过发版守卫）
./gradlew :app:compileDebugKotlin -PreleaseSkipVersionCheck=true -PreleaseSkipKeystoreCheck=true

# 构建调试包（产物在 app/build/outputs/apk/debug/，装 arm64-v8a 那个）
./gradlew :app:assembleDebug

# 单元测试（app/ai/memory/common/accessibility/material3 六个模块）
./gradlew :app:testDebugUnitTest

# 模拟器：MuMu（127.0.0.1:7555）。注意 insets≈0 怪癖，UI 顶部用 museSafeTopInsetPadding 兜底
```

### 测试基建注意

- 单元测试环境已配 `isReturnDefaultValues = true`（Logger 触达 `android.util.Log` 不再炸）
- Compose UI 测试用 Robolectric（`@Config(sdk=[33], qualifiers="w1600dp-h1000dp-xhdpi")`）

---

## 2. 模块与架构

| 模块 | 职责 |
|---|---|
| `ai` | AI 核心：Provider/Model/流式协议/工具协议。**无 UI 依赖** |
| `app` | 主应用：UI、渠道、工具、调度、数据层 |
| `memory` | 记忆系统：FactDB、反思、记忆星座 |
| `common` | 基础库：Logger 等 |
| `material3` | 设计系统过渡层 |
| `accessibility` | 无障碍支持 |

### 关键目录（app）

```
app/src/main/java/io/zer0/muse/
  ui/                 界面；ui/common/ 是设计系统组件库；ui/artifact/ 产物体系
  channel/            渠道：微信(iLink)/QQ(WebSocket)/飞书(pbbp2)/Telegram/钉钉
  tools/              工具注册、权限解析、审计
  schedule/           定时任务、主动消息
  data/               Room/Repository；artifact/ 产物、plugin/ 插件市场
  transformer/        提示词组装、模板渲染
  vision/             视觉桥（不支持原生视觉的模型降级）
  automation/         屏幕自动化（Shizuku/Root）
```

### 对话主链路（最重要的数据流）

```
UI(ChatScreen/ChatViewModel)
  → ChatService.streamChat (ai 模块)
    → buildProviderRequest（能力判定/工具装配/续传注入）
      → Provider（OpenAI 兼容 / Anthropic / Gemini）
        → 流式事件 Flow<ChatStreamEvent>
  → ChatStreamCoordinator（增量合并/去重/节流）
→ UI 渲染
```

**三条必须知道的契约**：

1. **工具发送判定 `shouldSendTools`**：`Model.abilities` 非空 → 以声明为准（含 TOOL 才发送）；空 → 交 `ProviderCompatRules` 兼容矩阵终裁（未知 ≠ 不支持）；**任何抑制都必须留日志**。
2. **模型目录字段语义**：`assets/model-catalog.json` 的 `toolUse` **缺省 ≠ 不支持**（默认值陷阱曾导致全量工具静默失效——见 §9）。改目录 schema 必须同步 `ModelRegistry.applyCatalogEntry` 与 `KnownModels` 逻辑。
3. **工具循环上限**：主链路与渠道自动回复均为最多 **5 轮**。

---

## 3. 关键子系统速览

### 3.1 模型与供应商

- 目录：`app/src/main/assets/model-catalog.json`（数百条，按 provider 分组；字段：name/context/maxOutput/image/reasoning/toolUse/compat）
- 兼容矩阵：`ai/.../ProviderCompat.kt`（thinking 格式、工具调用方言按 host/类型判定，拒绝硬编码白名单）
- 推理档位：`ReasoningLevel`（low/medium/high/xhigh/max，随 provider 适配）

### 3.2 工具系统

- 注册：`ToolRegistry` / `ToolRegistrarBootstrapper`；分类 `ToolCategories`；风险与权限 `ToolPermissionResolver`
- 审批：高风险工具走 `ToolApprovalCard`（UI 审批 + 审计账本 `ToolRiskLedger`）
- MCP：设置页 `McpSection`；外部工具命名前缀 `mcp_`
- 市场工具：`data/plugin/market/`（网关/信任根/签名校验）+ `PluginMarketToolsRegistrar`

### 3.3 渠道体系

五个平台，统一抽象在 `channel/`：

| 平台 | 接收方式 | 发送要点 |
|---|---|---|
| 微信 | iLink 长轮询 | 媒体：CDN 下载 + AES-128-ECB 解密；语音服务端 ASR |
| QQ | WebSocket Gateway | 被动回复需 msg_id + msg_seq；群消息 `group:` 前缀路由 |
| 飞书 | 长连接事件（手写 pbbp2 protobuf） | ping/ack/分片合并 |
| Telegram | 长轮询 | — |
| 钉钉 | Stream 模式 | — |

- **渠道 = 对话**：每会话独立上下文（`ChannelConversationStore`：多轮 + 滚动摘要自动压缩）
- 自动回复链路与主链路同规则（模板渲染/模型解析/工具循环）
- 媒体：`ChannelMediaUtils`；回复优先模型原生视觉，`VisionBridge` 降级描述兜底

### 3.4 记忆

- FactDB：按 scope 分库（`facts_<id>.db`）；每日反思检测矛盾记忆
- 记忆星座：主题聚簇的星图视图（MemoryScreen）
- 入口：设置页"记忆"；相关页面图标用 `brain`

### 3.5 定时任务与主动消息

- `ScheduledTasksScreen`（**注意**：列表内不要再套纵向滚动容器，MuseDialog 内容区已滚动——曾致崩溃）
- 主动消息：`ProactiveMessageRunner`；群聊调度：`GroupChatScheduler`

### 3.6 产物体系

- 消息内产物卡：全宽大卡纵列，**默认展示前 3 张**、点击展开全部（`MAX_VISIBLE_ARTIFACT_CARDS=3`）
- 产物中心（ArtifactCenterScreen）+ 打开链路（ArtifactOpenHost）
- 文档预览：`DocPreviewDialog`；PDF：`assets/docview/`（pdf.js viewer）

### 3.7 世界书与提示词

- 世界书注入：目标（system/user/assistant）× 位置（前置/后置/指定深度）；扫描深度/插入深度
- 模板渲染：`TemplateTransformer`（`{{char}}` 等占位符）
- 提示词模板：`assets/prompt_templates/`（默认人格、记忆规则；历史版本归档在 `legacy/`）

---

## 4. 设计系统与图标

### 4.1 组件库（ui/common）

**禁止在业务页面直接使用 M3 交互控件**（Button/Card/IconButton/TextField/Dialog…）——一律用 `ui/common` 的统一组件：

- 对话框/表单：`MuseDialog` / `MuseFormDialog` / `MuseBottomSheet` / `MuseSelectionSheet` / `MuseTextField`
- 按钮：`MuseTactileButton` / `MuseCapsuleButton` / `MuseFloatingButton` / `MuseTopBarIconButton`
- 列表/布局：`MuseListItem` / `CardGroup` / `MuseDivider` / `MuseLargeTitleHeader` / `MuseSearchBar`
- 尺寸/颜色 token：`MuseIconSizes` / `MusePaddings` / `MuseShapes` / `MuseActionColors`

**这条规范有 CI 护栏（CMP-11 `check_component_convergence.py`）**：`ui/**` 对 M3 控件的直接引用只降不升，新文件带 1 处直接引用就会被拦。

### 4.2 Muse Icons（自绘图标）

- 全部图标来自 `muse-icons` 仓库，**不再新增 Material/Tabler 图标引用**
- 使用方式：`MuseIcons.xxx`（静态 ImageVector，**非 Composable**，任意上下文可用）
  ```kotlin
  Icon(MuseIcons.search, contentDescription = null)
  // 需要 ImageVector 参数的自定义组件：
  MuseTopBarIconButton(icon = MuseIcons.arrowLeft, ...)
  ```
- 设计规格：24dp 栅格、1.7dp 圆头笔触、几何优先、currentColor 自适应
- 源数据：`muse-icons/icons.json`（**唯一数据源**）；预览：`https://zer0qing.github.io/muse-icons/`

**图标管线（改图标/加图标时）**：

```powershell
# 在 muse-icons 仓库
python scripts/build.py                    # icons.json -> icons/*.svg + docs/ 预览页
python scripts/to-vector.py --out "E:\1Project\Muse\1muse\app\src\main\res\drawable"    # -> ic_muse_*.xml
python scripts/to-compose.py --out "E:\1Project\Muse\1muse\app\src\main\java\io\zer0\muse\ui\common\icons\MuseIcons.kt"  # -> 静态入口
# 然后编译验证 + 提交两个仓库
```

**三条图标铁律**：
1. 资源名不能有连字符（`-` 自动转 `_`，`ic_muse_arrow_left.xml`）
2. 生成文件（MuseIcons.kt / ic_muse_*.xml / docs）**勿手改**，改 `icons.json` 后重生成
3. 图标复用要克制："不同语义的地方用同一图标"是体验债——新页面/新入口优先配语义相符的图标

---

## 5. 代码规范

- **文案**：UI 可见文本**一律走字符串资源**（`stringResource`），新增字符串必须补全 **7 语言**（zh/en/es/ja/ko/pt-rBR/ru）。CJK 硬编码有 CI 护栏（`check_hardcoded_cjk`，只降不升）
- **注释/KDoc**：中文，说明"为什么"而不是"是什么"；修复类注释标版本（如 `// v2.1.0: ...`）
- **提交信息**：`type(scope): 主题 — 明细`（中文），type 用 feat/fix/chore/refactor/release；一次提交一个主题，明细列要点
- **Compose**：滚动容器不要嵌套纵向滚动；预览/大列表内组件注意重组成本（关键值提为文件级或 remember）
- **Kotlin**：优先表达式风格；公开 API 加 KDoc；`internal` 收口模块内实现

---

## 6. 质量门禁（发布前必过）

**★ v2.1.0 起 CI 自动触发已关闭**。质量门禁与发版全部走**本地全量**：

```powershell
# 五条 lane 全跑（缺一不可！），在仓库根执行：
./ci/run_ci_checks.ps1 -Lane ci-scripts   # CI 脚本自身的测试
./ci/run_ci_checks.ps1 -Lane lanes        # 路由检查（含 hardcoded_cjk / localizations / touch_target 等）
./ci/run_ci_checks.ps1 -Lane static       # detekt / ktlint / lintDebug / 覆盖率
./ci/run_ci_checks.ps1 -Lane unit         # 六个模块单元测试
./ci/run_ci_checks.ps1 -Lane debug        # debug 构建
```

**基线机制（三个都要懂）**：

| 基线 | 文件 | 关键特性 |
|---|---|---|
| ktlint | `app/ktlint-baseline.xml`、`ai/ktlint-baseline.xml` | **行号敏感**：编辑文件后存量条目会失配报出 → 删掉 baseline 重跑一次自动重建 |
| detekt | `app/detekt-baseline.xml` 等 | 重建用 `./gradlew :app:detektBaseline` |
| lint | `app/lint-baseline.xml` | AGP lint，匹配不依赖行号 |
| 组件收敛 | `ci/component_convergence_baseline.json` | 只降不升；`--update-baseline` 收紧 |
| detekt 债务上限 | `ci/detekt_debt_cap.json` | **禁止只上调不清理**；上调必须写批次原因（note） |

**脚本清单**（`ci/script/`，static/lanes lane 会跑，也可单独跑）：

```
check_hardcoded_cjk.py       UI 硬编码中文（注意：它在 lanes lane 里）
check_translation_residue.py 非中文语言包 CJK 残留
check_font_scale_clipping.py 大字体裁切
check_component_convergence.py  组件收敛（CMP-11）
check_design_tokens.py      硬编码裸色
check_icon_content_description.py / check_touch_target.py / check_horizontal_inset.py
check_localizations.py / check_hardcoded_font_size.py
check_detekt_debt.py / check_ktlint_report.py
check_migration_coverage.py DB 迁移链硬校验
```

---

## 7. 发布流程

**版本规则：默认 patch +1**（2.1.0 → 2.1.1），除非有重大功能合并才升 minor。

```powershell
# ① 本地全量门禁五条 lane 全绿（见 §6）
# ② 版本线：app/build.gradle.kts 默认值 ?: 210 / "2.1.0" 改为新版本
# ③ release_body.md：写 1muse/releases/vX.Y.Z/release_body.md（仓库内，随提交）
# ④ 分批提交 + push main

# ⑤ 构建（★ 用环境变量注入，不要用 -P：pwsh 下 gradlew.bat 会拆坏 -PversionName=2.1.0）
$env:VERSION_NAME = "2.1.0"; $env:VERSION_CODE = "210"
./gradlew assembleRelease

# ⑥ 验签（需要 SDK 环境变量）
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
python ci/script/validate_release_apks.py --apk "app/build/outputs/apk/release/*.apk"

# ⑦ manifest 生成 + 校验（commit-sha 用最终发布 commit）
$apks = @(Get-ChildItem 'app/build/outputs/apk/release/*.apk' | Sort-Object Name)
$apkArgs = @(); foreach ($apk in $apks) { $apkArgs += @('--apk', $apk.FullName) }
python ci/script/generate_release_manifest.py --version-name "2.1.0" --version-code "210" @apkArgs `
  --db-file app/src/main/java/io/zer0/muse/data/session/MuseDb.kt `
  --release-notes "releases/v2.1.0/release_body.md" --commit-sha "$(git rev-parse HEAD)" `
  --source-ref "v2.1.0" --out release/manifest.json
python ci/script/validate_release_manifest.py --manifest release/manifest.json @apkArgs `
  --db-file app/src/main/java/io/zer0/muse/data/session/MuseDb.kt --tag "v2.1.0" --commit-sha "$(git rev-parse HEAD)"

# ⑧ 发布（幂等脚本：Release 已存在复用、资产一致跳过、远端核对）
python ci/script/publish_release.py --tag v2.1.0 `
  --body-file "releases/v2.1.0/release_body.md" `
  --artifacts "app/build/outputs/apk/release/*.apk" --artifacts "release/manifest.json"

# ⑨ 归档到 E:\1Project\Muse\releases\v2.1.0\
#    Muse_v2.1.0_{arm64-v8a,armeabi-v7a,universal}.apk + manifest.json + release_body.md + release_verification.txt
```

**CI 现状**：`.github/workflows/ci.yml` 保留，但自动触发（push/tag/schedule）**已全关**，仅保留手动 `workflow_dispatch`。历史原因见 §9。

---

## 8. 血泪经验（先看这节，能省几个小时）

1. **pwsh 5.1 读 UTF-8 无 BOM 脚本会按 ANSI 解码** → 中文 commit message 乱码、参数解析失败。**统一 pwsh 7 + `git commit -F -`（stdin 传 message）**。
2. **`gradlew.bat` 会拆坏 `-PversionName=2.1.0`**（`.1.0` 被当 task）→ 版本注入**用环境变量**（build.gradle.kts 优先级：-P > env > 默认值）。
3. **ktlint baseline 与行号强绑定**：编辑 Kotlin 文件后存量条目整批失配。**修正流程 = 先修自己引入的违规，再删 baseline 重跑自动重建**。
4. **`import androidx.compose.runtime.getValue/setValue` 是"文本隐形必需"**（`by` 委托隐式使用）——批量清理 unused import 时必须白名单保留，否则全量编译错误。
5. **Android 资源名不能有连字符**；图标文件名统一 `_`。
6. **模型目录 `toolUse` 字段缺省 ≠ 不支持**：`ToolUseSpec.supportsTools` 反序列化默认 false，曾导致所有命中目录的模型工具调用全量静默失效（toolsIn=0）。**能力目录的"缺字段"永远按"未声明"处理，并按"支持"保留**。
7. **v2.0.0 发版事故**：main+tag 双跑 + 45 分钟超时 → build job 被取消、release 未执行。此后：发版先 push main 等绿再 tag；CI timeout 提到 75；v2.1.0 起干脆关掉自动触发改本地全量。
8. **hardcoded_cjk 检查只在 `lanes` lane 里**——手动挑脚本跑会漏（本地全绿但 CI 红过的元凶）。**五条 lane 全跑**。
9. **`MuseDialog` 内容区已滚动**——内部不要再套 `verticalScroll`/`LazyColumn` 滚动容器（曾致"定时任务历史"对话框崩溃）。修改滚动结构后，全仓核查同类模式。
10. **CI 的 release job 与本地发布不要打架**：`publish_release.py` 是幂等的（已存在复用/一致跳过），双跑无害；但**能关自动触发就关**（v2.1.0 已关）。
11. **基线与生成物**：`MuseIcons.kt`、`ic_muse_*.xml`、`docs/index.html` 全部是生成物，改源（icons.json）后重新生成，不手改。
12. **模拟器（MuMu）insets≈0**：顶部安全区会让 UI 贴死，产品层用 `museSafeTopInsetPadding` 兜底。

---

## 9. 常见任务手册

**加一个新模型/供应商** → `assets/model-catalog.json` 加条目（name/context/image/reasoning/toolUse）；检查 `KnownModels` 是否需要补；编译 + 单测绿即可。

**加一个图标** →
```powershell
# muse-icons: 编辑 icons.json 加条目 -> 跑 §4.2 管线三个脚本 -> 到 1muse 编译 -> 提交两仓库
```

**加一个设置项** →
1. `SettingsScreen.kt` 的 `SettingsEntry`（含搜索关键词列表）+ 路由
2. 字符串 7 语言；图标从 `MuseIcons` 选**语义相符**的
3. 若有二级页：`SettingsNavGraph` 注册

**加一个 DB 迁移** → `MuseDb.kt` 版本 +1、写迁移链；`check_migration_coverage.py` 会硬校验（迁移必须有测试引用）。

**改提示词模板** → `assets/prompt_templates/`；历史版本移入 `legacy/`；注意 `SystemPromptAssembler` 的注入顺序。

**渠道调试** → 各平台的接收器在 `channel/`；实机验证参考：QQ 已连接日志、微信扫码状态机、飞书长连接的 ping/ack。

---

## 10. 外部资产与凭据

| 项 | 位置 | 备注 |
|---|---|---|
| 签名 keystore | 仓库根 `keystore.properties` | 本地；证书 SHA-256 与历史版本一致（可覆盖安装） |
| CI Secrets | GitHub 仓库 Secrets（KEYSTORE_BASE64 等 4 项） | 仅手动触发 CI 时使用 |
| gh CLI | 已认证 `Zer0Qing` | `publish_release.py` 依赖 |
| 发布归档 | `E:\1Project\Muse\releases\` | 仓库外；每版 6 文件（3 APK + manifest + 说明 + 校验记录） |
| 图标预览站 | `https://zer0qing.github.io/muse-icons/` | GitHub Pages（main/docs） |

---

*有任何文档与代码不一致的地方，以代码为准，并顺手把这里改对——文档的价值在于"始终可信"。*
