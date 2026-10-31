# 链路 10：文档 / 开源健康度 / 测试覆盖 审查报告

审查日期：2026-10-10（基于主线 24bf247f，工作树干净除 `.audit/`）
审查人：文档/测试/开源健康度链路子代理

## 摘要

本轮对 `E:\1Project\Muse\1muse` 的 10 份根目录文档、426 个 app/src/test 测试、9 个 androidTest、.gitignore/仓库卫生、NOTICE/LICENSE/许可清单、`gradle/libs.versions.toml` 依赖面，以及官网独立仓库 `E:\1Project\Muse\museai.ltd` 做了穷尽式审查。方法：全文读文档 → Grep 定位 v2.5.8 五功能（A2A/Tasker/插件 Hook/诊断导出/platform_selfcheck）生产代码 → Glob/Grep 核实测试覆盖 → 只读 git 命令核实跟踪状态 → 官网仓库逐文件核对版本链路。

总体结论：**v2.5.8 的五项新功能实现了"文档 0 覆盖 + 测试 0 覆盖"的双重真空**——A2A、Tasker、插件 Hook、诊断导出、platform_selfcheck 在 app/src/test 与 app/src/androidTest 中合计 0 个行为测试（唯一例外是 GenerationTrace 的字段级红线测试），且用户手册 19 章、README 双语、应用内 devdocs、官网 docs 全部未提及任何一项。另发现官网仓库 DEPLOY.md 把服务器 IP 与 SSH 私钥本机路径写进了 git 历史。根目录杂物（compile*.log 等）核实**未**被 git 跟踪，无问题。

**发现统计：P0 = 0，P1 = 8，P2 = 5，共 13 条。**

最重要的 3 条：F10a（官网 DEPLOY.md 凭据情报入库）、F2（A2A JSON-RPC 协议零测试 + 错误码缺陷 + 并发污染）、F6（搜索 13 家供应商解析零测试 + devdocs LLM 知识源漂移为"3 家"）。

---

## 发现清单

### F1. 诊断导出 zip 的内容红线无组合级测试（P1，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/diagnostic/DiagnosticExporter.kt:38-74`；`app/src/test/java/io/zer0/muse/diagnostic/GenerationTraceTest.kt:77-86`
- 证据：
  - `DiagnosticExporter.exportZip`（:38-74）打包三项：`generation-trace.jsonl` **原样** `copyTo(zos)`（:43-45，未按行校验）、`debug-log.txt` 为 `DebugLogStore.exportToFile()` 原样打包（:66-70）、`device-info.txt`。
  - `DebugLogStore` 是全局 Logger sink（`DebugLogStore.kt:23-27`："Logger.sink 自动把每条日志推送到 [log]"），缓冲全部 1000 条日志。
  - 唯一的红线测试 `GenerationTraceTest.kt:77-86` 只断言 **TraceRecord 序列化后的字段名** 不含 `content/prompt/message/text/body/apikey`；对 `DiagnosticExporter` 本身（Grep `DiagnosticExporter` 于 app/src/test → 零命中）以及 zip 三项内容的**值级**扫描（日志消息文本里可能出现的正文片段、token、key）没有任何断言。
- 影响：红线声明（DiagnosticExporter.kt:28 "不含消息正文、prompt、API key 原文"）依赖"上游各字段本身干净"这一未验证前提；Logger 消息里一旦将来混入异常 message（常含 URL/参数/key 片段）或 trace 文件被其他路径写入，导出即泄露且无测试拦截。
- 建议：补 Robolectric 测试——构造含已知敏感串（正文、`sk-…`、sessionId 原文）的场景，调用 `exportZip`，解包 zip 逐文件断言敏感串不出现（值级扫描，而非仅字段名）。

### F2. A2A JSON-RPC 端点零协议测试 + 错误码实现缺陷 + 并发污染（P1，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/web/WebServer.kt:648-707`；`app/src/main/java/io/zer0/muse/web/a2a/A2aProtocol.kt:117-124`；`app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:59-124`
- 证据：
  1. **零测试**：Grep `A2a|a2a` 于 app/src/test 与 app/src/androidTest → 0 命中。v2.5.8 旗舰协议（Agent Card + JSON-RPC，绑定在局域网 Web 端口上）没有任何畸形请求/错误码/方法分发测试。
  2. **`INVALID_REQUEST` 死码**：`A2aProtocol.kt:119` 定义 `const val INVALID_REQUEST = -32600`，全仓库唯一引用就是定义处（Grep `INVALID_REQUEST` → 仅 1 命中）。`handleA2aJsonRpc`（WebServer.kt:649-652）把**一切**解码失败（含"合法 JSON 但缺 method 字段"抛出的 MissingFieldException）一律映射为 `PARSE_ERROR(-32700)`。按 JSON-RPC 2.0，"不是合法请求对象"应返回 -32600，-32700 仅限"JSON 本身解析失败"。
  3. **id 字段语义混用（待验证假设，可信度中）**：GetTask（WebServer.kt:663-664）与 CancelTask（:670）把 JSON-RPC 信封的 `id`（请求关联符）直接当作 task id 使用，而 SendMessage 的 task id 来自 `params.message.messageId`——同一端点内"任务 id"的来源不一致，外部 A2A 客户端按规范把 task id 放 params 里将无法回查任务。
  4. **并发污染**：`A2aHandler.sendMessage`（A2aHandler.kt:59-124）无互斥——两个并发请求都会操作同一个 `chatViewModel`（:74 switchSession / :87-88 `updateInput(text); send()`），请求 B 的会话切换与输入框写入会打断/污染请求 A 的生成与回读（:104 按当前 session 取最后一条 assistant 消息）。
- 影响：畸形请求返回错误码与协议不符；多 agent 并发调用（A2A 的典型用法）结果互相串话；无回归防线。
- 建议：补 `A2aJsonRpcProtocolTest`（畸形 JSON / 缺 method / 缺 params / 未知 method / GetTask 不存在任务 → 断言错误码矩阵），并给 sendMessage 加单飞行互斥（或按 sessionId 排队）。

### F3. TaskerTriggerReceiver 零测试 + prompt 无长度/预算护栏（P1，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:42-48, 70-76, 97-98`；`AndroidManifest.xml:262-269`
- 证据：
  1. **零测试**：Grep `(?i)tasker` 于 app/src/test → 0 命中。intent 解析边界（action 不匹配、prompt 缺失/空白、extra 键名错误）全部没有行为断言。
  2. **无应用层截断**：`onReceive`（:44）`getStringExtra(EXTRA_PROMPT)?.trim()` 后直接整串进入 `chatService.completeText`（:70-76）并原样 `appendMessage` 落库（:97-98）。主链路有 ContextBudget/压缩管线，此路径完全绕过——一个约 500KB 的 extra（Binder 限额内可直接投递）会全量发给模型计费。
  3. **exported=true 已有取舍声明**（manifest :262-264 注释：跨应用广播必要 + 不授予工具执行）——按审查纪律不算发现；但注释声明的安全边界只覆盖"设备操作"，未覆盖**费用滥用面**：任何第三方应用可无限制广播触发 completion（无调用方校验、无节流、无开关），恶意应用可烧光用户 API 余额（待验证假设，可信度中——静态推断，未实测广播频率上限）。
- 影响：超长 prompt 直通模型产生大额费用；外部测试/回归无防线。
- 建议：补 Receiver 单测（Robolectric 可测 action/extra 边界），并在 runTrigger 前加 prompt 长度上限（如 4k 字符截断或拒绝）；考虑对同包外来源加节流。

### F4. 插件 Hook（onPromptFinalize）异常矩阵零测试（P1，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/data/plugin/PluginPromptFinalizeHook.kt:47-72`；`app/src/main/java/io/zer0/muse/data/plugin/PluginHookBridge.kt:33-69`
- 证据：
  1. **零测试**：Grep `PluginPromptFinalize|WebViewSkillEngine` 于 app/src/test → 0 命中。fail-open 契约（:34 注释"失败/返回不合法时保留原历史"）有 4 条分支：引擎抛异常（:51-55）、`SkillEngineResult.Error`（:56-59）、返回解析失败（:61-65）、返回空历史防空上下文（:66-69）——全部无行为断言。JS 侧异常被包装进 `__error`（:85-87）后如何呈现也无测试。
  2. **双协程并发 sync（待验证假设，可信度中）**：`start()`（PluginHookBridge.kt:33-39）启动两个 `appScope.launch`（collect 订阅 + 首次 sync），`sync()` 的 unregister-all → re-register 序列（:42-69）无互斥；两协程交错时同 id 幂等覆盖使最终态大概率一致，但注册表可能瞬时出现 hook 缺失窗口。HookRegistry.register 的同 id 覆盖语义已确认（HookRegistry.kt:42-46），故危害限于瞬时窗口。
  3. 注入面已封闭：`buildCallScript`（:75-89）中 content 经 `JsonPrimitive` 编码、role 走白名单 `wireName()`——无注入问题，不报。
- 影响：插件作者的常见错误（抛异常/返回 null/返回空数组）会静默退化为"保留原历史"，契约无测试锁定，未来重构易破坏 fail-open 语义。
- 建议：补 `PluginPromptFinalizeHookTest`（4 条 fail-open 分支 + 正常改写 + role 白名单），用假 entryCode 走 WebViewSkillEngine 或抽出纯解析函数测 `parseHistory`。

### F5. platform_selfcheck 零测试（P2，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/tools/PlatformSelfCheckTool.kt`（全文件）；`AppKoinModule.kt:389`（注册）
- 证据：Grep `platform_selfcheck|PlatformSelfCheck` 于 app/src/test → 0 命中。`execute`（:44-107）的输出拼接（node -v 结果分支 :55-60、npm 未就绪 :78、通道状态分支 :90-92、结论分支 :97-105）全部无断言。commit 24bf247f 交付该工具时仅附 ktlint-baseline 更新。
- 影响：v2.5.8 排障功能自身的文案/分支回归无防线；用户拿它排障时输出错误结论（如"运行时就绪 ✓"误判）无测试拦截。
- 建议：抽出输出拼装为可注入 `execNode` 结果的纯函数并补测试（Node 存在/缺失 × exec 成败 × 通道授权矩阵）。

### F6. 搜索 API 13 家供应商解析零测试 + devdocs（LLM 知识源）漂移为"3 家"（P1，可信度：高）

- 位置：`app/src/main/java/io/zer0/muse/web/WebSearchProviders.kt`（13 个 Provider 类：Baidu:40 / SearXNG:90 / Tavily:151 / Bing:532 / Jina:590 / Zhipu:657 / Brave:721 / Serper:784 / Bocha:849 / Metaso:918 / Exa:984 / Firecrawl:1058 / Perplexity:1129）；`app/src/main/assets/devdocs/web_search_guide.md:13, 59`
- 证据：
  1. **零解析测试**：Grep `WebSearchProvider|TavilyProvider|SearXNGProvider|BraveSearch|SerperSearch|ExaSearch|JinaProvider|Bocha|Metaso|Perplexity|Firecrawl` 于 app/src/test → 0 命中（WebSearchCoordinatorTest 仅测编排层，用假 service）。每家的 `parseResults` 都是手写 JSON 路径提取（如 Brave :758-773 `root["web"]["results"]`、Serper :824-834 `root["organic"]`），任何上游字段改名/嵌套变化 → `?: return emptyList()` **静默空结果**，用户与 LLM 均无感知。
  2. **devdocs 漂移**：`devdocs/web_search_guide.md:13`"搜索由三种 provider 实现…Bing(默认,免 key)/ SearXNG(自托管,免 key)/ Tavily(需 API key)"、:59"支持名: SearXNG/Tavily/Bing"——与代码 13 家严重不符。该文件头声明"基于源码真实实现，禁止编造不存在的 provider"（:5），且是 LLM 通过 knowledge_search 查询的真源：用户在应用里问"搜索支持哪些服务"会得到过时答案。
  3. 对照：根目录 `搜索API获取教程.md` 逐家列出 13 家（:3, :176-190），与代码一致——文档与 devdocs 之间自相矛盾。
- 影响：13 家商业 API 中任何一家改版都静默失效且无回归防线；LLM 真源教错。
- 建议：为 13 家各补一条"固定 JSON 样本 → 解析结果"解析测试（样本可取各家官方文档响应示例）；同步重写 devdocs/web_search_guide.md。

### F7. 用户文档对 v2.5.8 五功能零覆盖（P1，可信度：高）

- 位置：`用户手册.md`（939 行全文检索）；`README.md:136-246`；`README_EN.md`；`app/src/main/assets/devdocs/*`（17 篇）
- 证据：
  1. **用户手册**：Grep `A2A|Tasker|诊断导出|platform_selfcheck|onPromptFinalize|Hook` → 仅 :386"最近一次搜索诊断"（无关）。具体空缺：§14.2 插件节（:664-671）未提插件可声明 `hooks`/`onPromptFinalize`；§19.1 网页版（:840-846）未提 A2A 端点（`/.well-known/agent-card.json` 与 `/a2a`，JWT 鉴权）；§10/11 工具章未提 `platform_selfcheck`；§19 无 Tasker 广播触发入口（动作名/extra 用法只存在于 release_notes.md:7）。
  2. **README 双语**：功能一览五组（README.md:136-246）对 A2A/Tasker/插件 Hook/诊断导出自检零覆盖；`README_EN.md` 同构零覆盖（Grep 命中 0）。
  3. **应用内 devdocs 17 篇**：Grep 五关键词 → 仅 2 处无关命中（tools_guide.md:87 "网络诊断"、assistant_capability_playbook.md "自检流程"）；plugin_authoring_guide.md 完全没有 hooks/onPromptFinalize 章节（Grep 0 命中）——插件作者指南缺 v2.5.8 核心扩展点。
  4. `release_notes.md:2-12` 的 v2.5.8 条目齐全（好现象）；`软件功能.md` 已是 3 行跳转页（无漂移）。
- 影响：v2.5.8 是"外部协议接入与排障"主题版，但其用户（Tasker 用户、A2A 调用方、插件作者、排障用户）在仓库内与应用内均找不到任何操作指引——release_notes 是唯一知识源，恰是 DEDUPE 方向 2 点名的"用户视角文档教不会"。
- 建议：用户手册 §14.2 补插件 Hook、§19.1 补 A2A、§10 补 platform_selfcheck、新增 Tasker 触发小节；plugin_authoring_guide.md 补 hooks 章节；README"多助手与生态"组补一条 A2A/Tasker。

### F8. CONTRIBUTING.md 引用不存在的 AGENTS.md（P2，可信度：高）

- 位置：`CONTRIBUTING.md:49`
- 证据：`"遵循仓库根目录的 AGENTS.md(工程纪律与协作约定以该文件为准)"`——仓库根目录实际不存在 AGENTS.md（`Get-ChildItem` 根目录清单无此文件；`git ls-files AGENTS.md` 空；全仓 Glob `AGENTS.md` 0 结果）。
- 影响：贡献者按指南寻找"以该文件为准"的工程纪律文档会落空，与 CONTRIBUTING 其余内容的可执行性矛盾。
- 建议：删除该行或改为指向真实存在的纪律文档（如 docs/ENGINEERING_DISCIPLINE.md 若保留）。

### F9. NOTICE 与应用内许可清单未覆盖 v2.5.x 新依赖（P1，可信度：高）

- 位置：`NOTICE:40-68`；`app/src/main/assets/licenses/manifest.json:5-6, 全文`；`gradle/libs.versions.toml:110-112`；`app/build.gradle.kts:344-347`；`tools/runtime-pack/README.md:63-77`
- 证据：
  1. **backdrop / liquid 缺失**：`libs.versions.toml:111-112` 声明 `io.github.kyant0:backdrop 1.0.6` 与 `io.github.fletchmckee.liquid 1.1.1`，`app/build.gradle.kts:346-347` 引入，且实际使用（`LiquidGlass.kt:28-29` import liquid、`ChatScreen.kt:81` / `GroupChatDetailScreen.kt:75` import liquefiable）。但 NOTICE 依赖清单（:44-68）与 licenses/manifest.json 均无这两个库。
  2. **许可清单版本停滞**：`licenses/manifest.json:5-6` `"generatedAt": "2026-09-27", "appVersion": "2.1.0"`——该清单是应用内"开源许可"页的数据源（LicenseRepository.kt:14, :71），当前版本 2.5.8，落后 4 个 minor 版本（期间新增 backdrop/liquid 等）。
  3. **Node 运行时组件未入清单**：APK 分发的 Node 24.18.0/npm/OpenSSL/ICU/c-ares/sqlite/zlib（runtime-pack README:63-74）只在 runtime-pack README:76-77 有一句笼统声明（"各组件保留其原始开源许可证"），licenses/manifest.json 完全不含它们；assets/licenses/ 下仅 5 份通用许可文本（apache-2.0/mit/bsd-2/bsd-3/epl-1.0），MIT/ISC 组件（OpenSSL 为 Apache-2.0、c-ares 为 MIT、zlib 为 zlib 许可）的对应文本与署名未随包分发。
- 影响：GPL v3 项目的分发物对终端用户展示的第三方许可清单不完整——既有法律合规瑕疵，也违背项目自建的"应用内许可页"机制初衷。
- 建议：licenses/manifest.json 补 backdrop/liquid 并把 appVersion 刷新机制纳入发版清单（release 流程已有 generate_release_manifest 钩子可挂）；Node 运行时组件（至少 Node/OpenSSL/ICU/c-ares/zlib）补入清单与对应许可文本。

### F10a. 官网仓库 DEPLOY.md 把服务器 IP 与 SSH 私钥路径写入版本库（P1，可信度：高）

- 位置：`E:\1Project\Muse\museai.ltd\DEPLOY.md:3-4`（已跟踪，`git ls-files` 确认；commit 839dfb3 "docs: 记录部署凭据与可直接执行的 Windows 部署流程"）
- 证据：
  ```
  服务器：`root@117.72.212.102`（Ubuntu / nginx，站点根目录 `/var/www/museai.ltd`）
  SSH 私钥：`C:\Users\21192\Downloads\HANA.pem`（RSA，`ssh -i` 直接用；**本机路径，勿入库/勿外传**）
  ```
  文件自身标注"勿入库"却已入库并被跟踪（自相矛盾）；部署命令块（:19-46）内含 root 口径的完整运维链路。仓库当前无 git 远端（DEPLOY.md:6 自述"不是 git push"），但该目录是独立 git 仓库，历史与工作树均含此情报。
- 影响：一旦该目录被推到任何公开/半公开托管（与主仓库同盘同目录，误操作面大），服务器 root 目标与私钥位置即公开，成为针对性攻击面。
- 建议：从跟踪中移除 DEPLOY.md（`git rm --cached` + .gitignore），改写为脱敏版部署手册；服务器侧建议轮换 SSH 授权（因情报已在本地历史多份副本中存在）。

### F10b. 官网下载页/首页硬编码兜底版本停在 v2.3.0（P2，可信度：高）

- 位置：`E:\1Project\Muse\museai.ltd\download\index.html:53-54`；`index.html:47`
- 证据：`<span id="dl-version" ...>v2.3.0</span>`、`<span id="dl-date" ...>2026-09-27</span>`（下载页）；首页 hero 同为 v2.3.0。当前版本 v2.5.8（release_notes.md:2）。正常路径 `js/main.js:151` 会用 `/api/latest.json` 覆盖，但 fallback 链（main.js:153-156 注释"都失败：保留硬编码版本"）下——`/api/` 同源缓存失败 + api.github.com 国内超时是 main.js:86 自述的常见情形——用户看到落后 5 个版本的页面。下载按钮兜底 href 指向 releases 列表页（download/index.html:62-72），不会死链。
- 影响：兜底态展示过期版本号与日期，弱化更新感知；属展示层卫生问题（数据链路本身有服务端 systemd 同步，设计合理）。
- 建议：发版脚本把硬编码版本一并刷新，或将兜底值改为"最新版本"占位文本。

### F10c. 官网 docs 10 篇文档对 v2.5.8 功能零覆盖（P2，可信度：高）

- 位置：`E:\1Project\Muse\museai.ltd\docs\**\*.html`（10 个模块指南 + 快速上手 + FAQ）
- 证据：全目录扫描 `A2A|Tasker|诊断导出|平台自检|onPromptFinalize` → 0 命中。官网文档中心与主仓库用户手册同步漂移（F7），对外官网同样教不会。
- 影响：官网是国内用户的首选入口（README.md:83"国内直连，推荐"），文档与新版本功能脱节。
- 建议：官网 docs 与用户手册同步补 A2A/Tasker/插件 Hook/诊断导出条目（可与 F7 一并安排）。

### F11. 搜索API获取教程.md 为孤立文档（全仓零引用）（P2，可信度：高）

- 位置：`搜索API获取教程.md`（190 行，commit 24bf247f 交付）
- 证据：Grep `搜索API获取教程` 于全仓（含 README/用户手册/devdocs/设置页 Kotlin）→ 0 命中；Grep `获取教程|搜索API` 于 app/src/main → 0 命中。文档自身 :5 给出入口"设置 → 搜索（Web 搜索）"，但没有任何现有文档或应用入口链接到这份 13 家供应商的申请教程。
- 影响：commit 标题即"内置搜索 API 获取教程"，但用户与 LLM 都无路径发现它——内容白做，且与 F6 的 devdocs 漂移叠加（devdocs 说 3 家、教程说 13 家、互相矛盾）。
- 建议：在用户手册 §7.1、devdocs/web_search_guide.md 与设置页搜索区各加一处引用（或把要点并入 devdocs）。

---

## 已知项核对（确认不重复上报）

| 已知项 | 本轮核对结论 |
| --- | --- |
| 五个非英语语言包各缺 101 翻译 | 未涉翻译面，未报 |
| P1 #43"同名测试缺失不是覆盖率证据" | 本轮全部按"缺关键行为断言 + 实现缺陷实证"口径上报，非同名文件论 |
| SECURITY.md / docs 跟踪策略需仓库所有权决策 | 未作独立发现；**补充新证据**：README.md:298 与 README_EN.md:297 均把 [SECURITY.md](SECURITY.md) 作为公开入口链接，而该文件被 .gitignore:37 忽略且未跟踪（`git ls-files` 空）——克隆仓库的访客点击即 404。注意 CONTRIBUTING.md 在 .gitignore:35-36 有"忽略+豁免"成对规则，SECURITY.md 只写忽略了（:37）无豁免行 |
| CDN 镜像 404 与证书续期属外部运维 | 官网最新提交 84d3022 已改为 GitHub Releases 直链（main.js:111-121），CDN 链路已退场，该已知项现状良好；本轮未报 CDN |
| 诊断导出不记录正文/key 是既定红线（全局已知 13） | 未报"红线未实现"；F1 报的是红线**缺组合级测试**，属基线明示允许的检查方向 |
| 插件沙盒放开是 v2.5.8 产品决策（全局已知 12） | F4 只涉及 Hook 体系测试缺失，未涉沙盒放开本身 |
| 文档纪律（README 无 emoji、事实对代码）与交接文档同步 | 已遵守；本轮报的是**覆盖缺口**（v2.5.8 功能无章节），非纪律违例 |
| detekt/ktlint 存量、设备验收缺失、翻译缺口 | 均未触及 |

**核实后排除的疑点**（探查过但结论为无问题）：
- 根目录 `baseline_compile.log` / `compile2-4.log` / `app/hs_err_pid46000.log`：`git ls-files '*.log'` 为空、`git check-ignore -v` 确认全部被 .gitignore:15 `*.log` 覆盖——**未被跟踪，无发现**（基线方向 4 的答案是"已覆盖"）。
- 用户手册.md / 软件功能.md / 搜索API获取教程.md / UI组件库维护规范.md 均已被 git 跟踪；README 头图与全部截图/收款码引用路径实测存在。
- `搜索API获取教程.md` 13 家与代码 13 个 Provider 类一一对应（数量无漂移，漂移在 devdocs）。
- `.gitattributes`、gradle.properties、ci/（55 个文件跟踪）、run-all-tests.ps1 均正常跟踪。
- CONTRIBUTING.md 指向的 `./ci/run_ci_checks.ps1` 与各 Lane 存在；ci.yml 与 web-ci.yml 已跟踪。

## 方法与覆盖说明

- 工具：Glob/Grep/Read 为主；仅仓库卫生与官网仓库使用只读 git/pwsh 命令（`ls-files`/`check-ignore`/`status`/`log`），未修改任何文件。
- 测试覆盖核实路径：app/src/test 426 个文件、app/src/androidTest 9 个文件分别按 A2A / Tasker / Diagnostic / onPromptFinalize / 13 家供应商名 / platform_selfcheck 六组关键词检索，另抽查 WebSearchCoordinatorTest、GenerationTraceTest 全文确认其覆盖边界。
- F2-3（GetTask 用信封 id 当 task id 是否违反 A2A 1.0 规范）与 F3-3（第三方广播无节流）为静态推断，标注为待验证假设并附验证方法（前者对照 a2a 规范 tasks/get 参数定义，后者实测广播频率）。
