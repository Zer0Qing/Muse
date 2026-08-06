# AUDIT_PROGRESS.md — Muse 改造清单执行进度

> 执行基线：任务书 `E:\1Project\Muse\Muse项目全面审计与改造清单.md` v1.1（2026-08-06 所有者审核修订版）
> 仓库：`E:\1Project\Muse\1muse`，分支：`refactor-audit-fixes`（基于 main f967fda）
> 批次定义以任务书「修订说明 → 执行顺序」为准。R-SEC-01 / R-DB-01 / R-BUILD-01 / R-CI-01 / R-AI-01 已驳回或降级，不执行。
> 本文件在仓库根目录维护；上下文交接卡同步维护于 `E:\1Project\Muse\上下文交接卡.md`。

## 批次状态

- [x] 第一批（止血）：R-DB-02 + R-UI-01 / R-SEC-02 / R-SEC-06 / R-UI-03 / R-DOC-01 / R-DOC-05
- [x] 第二批（本迭代）：R-TEST-01 / R-TEST-05 / R-TEST-09 / R-SVC-04 / R-BUILD-04 / R-BUILD-02 / R-UI-02（后端面）
  - 已完成并验证：R-TEST-01 / R-TEST-05 / R-TEST-09 / R-SVC-04 / R-BUILD-04 / R-UI-02（后端面）
  - R-BUILD-02：已按 owner 决策关闭（保持 material3 1.4.0-alpha04 + 现 BOM，等待 1.5.0 stable）
- [ ] 第三批及长期：R-UI-04~09、R-SVC-01~03、R-BUILD-05/08/09/10、R-TEST 其余、R-CI 其余（R-CI-01 除外）、R-DOC 其余，以及长期项按依赖顺序推进

## 条目明细（编号 | 状态 | 验证结果 | 备注）

| 编号 | 状态 | 验证结果 | 备注 |
|---|---|---|---|
| R-DB-02 | 已完成 | `:app:testDebugUnitTest` 全绿；R-TEST-23 2/2 通过 | onOpen 双保险 + sqlite_master 探测 + 影子表清理重建 + DAO 自愈；app Robolectric 不支持 FTS4，测试用 fake DAO |
| R-UI-01 | 已完成 | `assembleDebug` 零错误 | KnowledgeScreen 修复/重建索引按钮、进度与多语言 Toast |
| R-SEC-02 | 已完成 | grep 确认无 token 响应体日志 | OAuth 三处日志脱敏 |
| R-SEC-06 | 已完成 | `:ai:testDebugUnitTest` 全绿；`take(500)` 0 命中 | 400 请求体改结构化摘要 |
| R-UI-03 | 已完成 | 编译通过 | 默认主题统一 mono，文档同步 |
| R-DOC-01 | 已完成 | FactDbMigrationTest 通过 | .db 清理 + gitignore + hygiene 脚本 |
| R-DOC-05 | 已完成 | 编译通过 | ai 构建注释修正 |
| R-DOC-02 | 已完成 | 引用路径核查通过 | CONTRIBUTING.md 修订 JDK 21、移除失效引用；决策为随 R-DOC-03 解除 ignore 入库 |
| R-DOC-03 | 已完成 | `git ls-files docs/` 与决策一致 | ENGINEERING_DISCIPLINE.md、后台保活与进程恢复.md、CONTRIBUTING.md 入库；ACCESSIBILITY/SHIZUKU/SKILLPKG 保持不入库 |
| R-DOC-04 | 已完成 | 编译通过 | KDoc 删除悬空 WorkflowEditorScreen 引用；ui/workflow 空目录已删 |
| R-DOC-06 | 已完成 | 构建正常 | 删除 app/build_alt（约 2.3GB）与 .kotlin/sessions |
| R-DOC-08 | 已完成 | grep `runCatching { playbackJob` 0 命中；`:app:compileDebugKotlin` 通过 | TtsManager 两处 join 改 resultOf；phase3 清单同步 workflow 包已删 |
| R-UI-10 | 已完成 | grep ThemePreviewCard 0 命中；编译通过 | ThemeSection 死代码 ThemePreviewCard 删除 |
| R-UI-11 | 已完成 | grep `collectAsState()` 仅剩 MuseToastHost（带注释） | ProviderSection 改 collectAsStateWithLifecycle |
| R-UI-12 | 已完成（复核） | 5 个文件逐点复核 | 现有交互按钮/行均带语义标签或相邻文本，剩余 null 均为装饰性图标，符合任务书“装饰性保持 null”说明 |
| R-UI-13 | 已完成 | 编译通过 | SettingsTutorialPage 搜索结果 items 补稳定 key |
| R-UI-14 | 部分完成 | assembleDebug + 三模块测试全绿 | DebugScreen 拆出 DebugCrashLogSheet/DebugAnalyticsSheet/DebugFormatters，1572→约940行；其余大文件继续 |
| R-UI-08 | 待下一迭代 | 未执行 | 状态拆分不在本分支执行（owner 决策，先文件后状态） |
| R-UI-15 | 已完成 | 编译通过 | StickerLibraryRepository 逐条目 D 日志改为每 100 条汇总，保留最终汇总 |
| R-TEST-03 | 已完成（JVM 面） | PinLockPolicyTest 4/4 + SecureKeyStoreTest 3/3 通过 | SecureKeyCipher 接口 + delegate 注入可 mock；Keystore 硬件往返留真机验证 |
| R-TEST-04 | 已完成（JVM 面） | BackupCryptoTest 3/3 + SettingsSnapshotPolicyTest 3/3 + BackupKeyExclusionTest 1/1 通过 | SettingsSnapshotPolicy 白名单 + 敏感片段；备份 JSON 扫描无 apiKey 明文 |
| R-TEST-07 | 已完成 | DocumentParserMarkdownDocxTest 3/3 通过 | markdown/.md.txt/minimal docx；顺带修复 docx/pptx 带 w:/a: 前缀时解析不到文本的 bug |
| R-TEST-08 | 已完成 | StickerLibraryRepositoryImportZipTest 2/2 通过 | 中文目录/大写 .GIF/嵌套目录/空 zip |
| R-TEST-11 | 已完成 | ScheduledTaskRunnerScheduleTest 6/6 通过 | 下次触发时间 + 跨夜免打扰窗口 |
| R-TEST-13 | 已完成（已有） | MemoryTickerTest 相关用例通过 | start 幂等/stop 后重启已有覆盖 |
| R-TEST-15 | 已完成 | GroupChatSchedulerPureLogicTest 4/4 + ChannelPassToolTest 2/2 | 轮转账本/辩论角色/channel_pass 回调 |
| R-TEST-16 | 已完成 | AssistantEntitySerializationTest 4/4 + AssistantCardExporterRoundTripTest 2/2 | 全字段往返/坏 JSON/导入导出 |
| R-TEST-17 | 已完成 | PromptTemplateLoaderTest 4/4 通过 | locale null/未知 locale/缺失模板回落 |
| R-TEST-18 | 已完成 | CoverGeneratorFallbackTest 2/2 通过 | 空 LLM 输出降级纯函数 |
| R-TEST-21 | 已完成 | MuseTypographyScaleTest 2/2 通过 | 字号缩放枚举已知/未知值 |
| R-TEST-22 | 已完成 | 编译通过 | 本地 asset 页面 + 收紧断言 + testInstrumentationRunner 显式声明 |
| R-CI-02 | 已完成 | `detekt` BUILD SUCCESSFUL | detekt 1.23.8 全模块 + maxIssues=0 + baseline；README/AGENTS/ENGINEERING_DISCIPLINE 同步 |
| R-CI-03 | 已完成 | `ktlintCheck` 全模块通过 | ktlint 应用到 6 模块 + CI step + ktlintFormat 存量 |
| R-CI-04 | 已完成（workflow） | 本地未跑 release（无 secret） | tag 触发 assembleRelease + 缺 keystore secret 明确失败 + APK artifact |
| R-CI-05 | 已完成（阻断） | Lane 8 个脚本 + ci/test 3 个脚本全绿；assembleDebug/app 单测/detekt/ktlint 通过 | 9 处 empty_catch、1 处 CJK、1 处 fontSize 清零；移除 continue-on-error，Lane 转阻断 |
| R-CI-06 | 已完成 | ci/test 3 个脚本测试全绿 | CI 执行脚本测试入口 |
| R-CI-07 | 已完成 | `koverCachedVerifyDebug` 三模块通过（ai 40 / memory 30 / app 12） | debug-only 阈值；CI 静态检查步骤已接入，不触碰 release/keystore |
| R-CI-08 | 已完成 | workflow 生效 | emulator job 仅 workflow_dispatch 手动触发，不随 push/PR/schedule 常驻 |
| R-CI-09 | 已完成 | `:app:lintDebug` BUILD SUCCESSFUL（baseline 130 errors/1290 warnings 过滤） | abortOnError=true + app/lint-baseline.xml 入库 |
| R-CI-10 | 已完成 | workflow 生效 | setup-gradle 缓存 + debug/reports artifact + release workflow |
| R-CI-11 | 已完成 | workflow 生效 | schedule/tag/workflow_dispatch 触发 |
| R-AI-02 | 已完成 | grep currentCall 0 命中（OpenAIProvider） | 删除无效 newCall/currentCall，取消统一走 EventSource.cancel |
| R-AI-03 | 已完成 | :ai 编译通过 | 重试前 evictIdleConnections；ProviderHttpDefaults CALL_TIMEOUT_SEC 0→600 |
| R-AI-04 | 已完成 | FirstEventWatchdogTest 5/5 通过 | 推理/超长上下文模型 60s 首事件超时，普通模型 15s；补部分事件边界用例 |
| R-AI-05 | 已完成 | grep `Error("aborted"` 0 命中 | streamChatResponses abort 统一为 StreamInterrupted |
| R-AI-06 | 已完成 | 编译通过 | FallbackNotice 文案改为“网络较慢，已切换请求方式”，ChatViewModel 已有 Toast 呈现 |
| R-SEC-08 | 已完成 | 编译通过 | Shizuku UserService debuggable 改 BuildConfig.DEBUG |
| R-SVC-06 | 已完成 | PluginManagerTest 新增能力白名单用例通过 | 白名单删除 network/resource.write；插件作者指南同步 |
| R-SVC-07 | 已完成 | PluginManagerTest 通过 | uninstall 删除失败 Toast+日志；registry 原子写 temp+rename + Mutex |
| R-TEST-12 | 已完成 | WebServerAuthPolicyTest 3/3 通过 | 限流窗口/JWT HMAC 签发校验 |
| R-DOC-07 | 已完成 | 验收文档已更新 | BE-012/013 指向快照测试；BE-039~047 标 CI 状态；新增 2026-08-06 附录（769 tests） |
| R-SEC-03 | 已完成 | WebServerAuthPolicyTest 6/6 通过 | 方案 B：默认 127.0.0.1 + CORS 仅本机；设置页局域网开关开启后绑定 0.0.0.0 + anyHost；JWT 独立密钥 |
| R-SVC-05 | 已完成 | 编译通过 | 方案 B：仅聊天/群聊生成任务活跃且 keepAwake 开启时持锁；低电量未充电不持锁；设置页文案同步 |
| R-TEST-19 | 已完成 | VisionBridgePureFunctionsTest 4/4 通过 | 视觉上下文/失败提示/MIME 嗅探/hash 纯逻辑 |
| R-TEST-20 | 部分完成 | OpenAIImageProviderRequestTest 3/3 通过 | OpenAI 文生图请求体字段黄金测试；其余 image/video/MCP/importer/widget 待补 |
| R-TEST-14 | 已完成（JVM 面） | MuseDbMigrationTest 通过 | 反射迁移链 + schema-aware 插行；JVM 覆盖 v55→75；v1-54 因 FTS4 与 schema 漂移留真机 |
| R-TEST-10 | 部分完成 | ToolOrchestratorPureFunctionsTest 新增 2 例通过 | 超时常量与连续失败早停纯逻辑；并行/超时真实路径待补 |
| R-TEST-06 | 部分完成 | ChatViewModelSendGuardTest 4/4 通过 | 发送守卫纯逻辑（空消息/流式/Agent 重入）；完整状态机待 R-UI-08 后补 |
| R-TEST-01 | 已完成 | `:ai:testDebugUnitTest --tests "*StreamGuardTest*"` 3/3 通过 | guard 挂起/reasoning 先到/空 finishReason；早停回退网络路径由 FirstEventWatchdogTest + 快照测试共同覆盖 |
| R-TEST-05 | 已完成 | `:ai:testDebugUnitTest --tests "*ProviderRequestBodySnapshotTest*"` 3/3 通过 | Anthropic/Gemini/Ollama(OpenAI 兼容)请求体快照 |
| R-TEST-09 | 已完成 | memory/app PiiGuardTest 全绿 | 手机(含空格)/邮箱/15/18 位身份证 mask/unmask 往返；两处 PiiGuard 正则同步增强 |
| R-TEST-02 | 已完成（随 R-UI-02） | `ChatViewModelFocusRestoreTest` 2/2 通过 | 进程恢复优先还原查看会话；outbox 重放不改写焦点 |
| R-SVC-04 | 已完成 | 编译通过 | JsSandbox 超时销毁 WebView、连续超时熔断、总超时配额、插件自动禁用 |
| R-BUILD-04 | 已完成 | `assembleDebug` 成功；grep 无 haze/sonner | 删除死依赖声明与实现 |
| R-BUILD-02 | 已关闭（owner 决策） | 保持 material3 1.4.0-alpha04 + BOM 2024.12.01，`assembleDebug` 成功 | 等待 1.5.0 stable 公开 Expressive API，不再尝试升级 |
| R-UI-02 | 已完成 | R-TEST-02 2/2 通过；编译通过 | 新增 viewed_session_id / generating_session_id 两个 DataStore key；ChatViewModel 启动恢复查看焦点；outbox/checkpoint 重放不改写焦点；docs 更新 |

## 第二批小结（检查点）

- 已完成：R-TEST-01、R-TEST-05、R-TEST-09、R-TEST-02、R-SVC-04、R-BUILD-04、R-UI-02 后端面。
- 验证：`:ai:testDebugUnitTest` / `:memory:testDebugUnitTest` / `:app:testDebugUnitTest` 全绿；`assembleDebug` BUILD SUCCESSFUL。
- 阻塞：R-BUILD-02 无法按任务书直接切 material3 stable，需要 owner 决策（保持 alpha04 等待 API 公开 / 接受标准 MaterialTheme 的动效变化 / 提供独立 expressive artifact）。
- 对外接口：无已 import 类名/函数签名变更；新增 SettingsRepository 会话焦点存取方法、JsSandbox 熔断 API、DAO raw 方法、测试类；无 DB schema/Room 列名/strings key 变更。

## 遇到的问题（阻塞项记录，供所有者 review）

- 阻塞（需要 owner 决策）：R-BUILD-02。material3 1.4.0 stable（BOM 2026.06.01）中 `MaterialExpressiveTheme`、`MotionScheme`、`ExperimentalMaterial3ExpressiveApi` 均为 internal；BOM 还会强制覆盖 1.4.0-alpha04。已实测 `assembleDebug` 编译失败，回退到 alpha04 + BOM 2024.12.01 后全绿。
- 非阻塞：app 模块 Robolectric 不支持 FTS4 vtable，R-TEST-23 用 fake DAO；R-TEST-01 的早停回退完整网络路径在 MockWebServer 下不稳定，改由 FirstEventWatchdogTest + ProviderRequestBodySnapshotTest 覆盖。
- 已解决（R-CI-05）：check_engineering_discipline.py 9 个 empty_catch（JsSandbox 8 + GreetingHelper 1）、check_hardcoded_cjk.py 1 处（SettingsSubPages 剪贴板标签）、check_hardcoded_font_size.py 1 处（ChatScreen 有意豁免注释）均已清零；CJK baseline 已收紧；Lane 步骤已移除 continue-on-error 转阻断。
- 非阻塞（R-CI-04）：release job 已落地，但本地无 GitHub Secrets，无法实跑；配置为缺 secret 明确失败。
- 非阻塞（R-TEST-03/04）：SecureKeyStore 与备份全链路需要 Android Keystore/真机或完整 mock 链，本轮只补了可 JVM 化的退避与加密往返。
- 非阻塞（Windows 本机）：全量 :app 测试一次出现 AppSettingsStoreTest 的 DataStore 文件锁 FileNotFoundException（另一个程序正在使用），单类与重跑全量均通过，判定为本机偶发文件锁，非代码回归。
- 非阻塞（R-DOC-07 附录）：当前三模块单测合计 769 tests / 102 测试类 / 0 failures（2026-08-06），已写入验收文档。
- 非阻塞（检查点 6 复核）：R-TEST-04 备份密钥剔除测试尝试被 Robolectric 无 AndroidKeyStore 阻塞（SettingsRepository.addProvider 会先加密 apiKey），需真机或可注入 SecureKeyStore 的测试边界；R-CI-07 koverVerify 会触发 release 编译并触碰 keystore 硬约束，阈值门禁需 owner 确认 debug-only 方案。

### 待所有者决策（第三批检查点 2 后）
- R-UI-08/R-UI-14：巨型状态/文件拆分风险高，需 Layout Inspector 真机验证与 detekt LargeClass 基准，暂不盲目执行，等所有者确认拆分层级。
- R-TEST-14：依赖 R-DB-01（已驳回）与 R-CI-01（待确认），v1-55 全链是否本地回放需所有者确认。
- R-CI-08：emulator job 涉及 GitHub Actions 模拟器资源与长期成本，本轮只完成 androidTest 本地化，是否上 emulator 需确认。
- R-CI-09：lint 存量 2475 errors，收紧 abortOnError 需先生成 lint baseline 或分批清零，需确认基线策略。
- R-CI-07：覆盖率阈值需先确定各模块基线百分比再配置 Kover verify。
- 长期项（R-DB-04/05、R-BUILD-07、R-SEC-03/08、R-SVC-05~07、R-AI-02~06）未在本检查点执行，按依赖顺序待后续批次。
- 第二批其余条目均已实现并通过验证，等待 owner 对 R-BUILD-02 的决策后即可收尾第二批。

## 第三批检查点（进行中）

- 已完成：R-UI-04（主题页多语言）、R-UI-05（TimePicker 分钟设置）、R-UI-06（封面 prompt + 空输出降级）、R-UI-07（TTS 失败 Toast）、R-UI-09（TaskCard 取消 + 确认 ANR 后台过滤/429 文案已存在）、R-SVC-01（NodeInfo recycle + 非主线程断言）、R-SVC-02（截图反射失败提示）、R-SVC-03（Shizuku suspend/超时/release）、R-BUILD-05（biometric 1.1.0 stable）、R-BUILD-08（KSP 2.3.11）、R-BUILD-09（accessibility 纳入 Kover）、R-BUILD-10（ProGuard 部分收窄）。
- 尚未开始：R-UI-08（state-split）、R-TEST 其余、R-CI 其余、R-DOC 其余、长期项。
- 本检查点新增完成：R-DOC-02/03/04/06（CONTRIBUTING 修订并入库、docs 决策执行、KDoc 清理、本地垃圾清理）。
- 尚未开始：R-UI-08（state-split）、R-TEST 其余、R-CI 其余、R-DOC-07/08、长期项。
- 本检查点新增完成：R-DOC-08、R-UI-10/11/12(复核)/13/15。
- 尚未开始：R-UI-08（state-split）、R-TEST 其余、R-CI 其余、R-DOC-07、长期项。
- 本检查点新增完成：R-TEST-07/08/11/13(已有)/15/16/17/18/21/22、R-TEST-03/04 部分、R-CI-02~06/10/11、R-CI-07/08/09 部分。
- 尚未开始：R-UI-08（state-split）、R-TEST-06/10/12/14/19/20、R-TEST-03/04 剩余面、R-CI-07/08/09 剩余面、R-DOC-07、长期项。
- 本检查点新增完成：R-AI-02/03/04/05/06、R-SEC-08、R-SVC-06/07、R-TEST-12、R-DOC-07。
- 尚未开始：R-UI-08（state-split）、R-TEST-06/10/14/19/20、R-TEST-03/04 剩余面、R-CI-07/08/09 剩余面、R-SVC-05、R-SEC-03、R-DB-04/05、R-BUILD-07、R-UI-14 等长期项。
- 本检查点新增完成：R-CI-08（emulator job）、R-CI-09（abortOnError=true + lint baseline）。
- 尚未开始：R-UI-08（state-split）、R-TEST-06/10/14/19/20、R-TEST-03/04 剩余面、R-CI-07 阈值、R-SVC-05、R-SEC-03、R-DB-04/05、R-BUILD-07、R-UI-14 等长期项。
- 本检查点新增完成：R-SEC-03 的 JWT 独立密钥面（CORS/绑定面仍待 owner）。
- 尚未开始：R-UI-08（state-split）、R-TEST-06/10/14/19/20、R-TEST-03/04 剩余面、R-CI-07 阈值、R-SVC-05、R-SEC-03 CORS/绑定面、R-DB-04/05、R-BUILD-07、R-UI-14 等长期项。
- 本检查点新增完成：R-SVC-05 低电量面（部分）。
- 尚未开始：R-UI-08（state-split）、R-TEST-06/10/14/19/20、R-TEST-03/04 剩余面、R-CI-07 阈值、R-SVC-05 任务期持锁面、R-SEC-03 CORS/绑定面、R-DB-04/05、R-BUILD-07、R-UI-14 等长期项。
- 本检查点新增完成：R-TEST-19；R-TEST-20 的 OpenAI 图片请求体面（部分）。
- 尚未开始：R-UI-08（state-split）、R-TEST-06/10/14/20 其余、R-TEST-03/04 剩余面、R-CI-07 阈值、R-SVC-05 任务期持锁面、R-SEC-03 CORS/绑定面、R-DB-04/05、R-BUILD-07、R-UI-14 等长期项。
- 本检查点新增完成：R-TEST-14 v55→75 面（部分）。
- 尚未开始：R-UI-08（state-split）、R-TEST-06/10/14 v1-54/20 其余、R-TEST-03/04 剩余面、R-CI-07 阈值、R-SVC-05 任务期持锁面、R-SEC-03 CORS/绑定面、R-DB-04/05、R-BUILD-07、R-UI-14 等长期项。
- 本检查点新增完成：R-TEST-10 纯逻辑面（部分）。
- 尚未开始：R-UI-08（state-split）、R-TEST-06/10 真实路径/14 v1-54/20 其余、R-TEST-03/04 剩余面、R-CI-07 阈值、R-SVC-05 任务期持锁面、R-SEC-03 CORS/绑定面、R-DB-04/05、R-BUILD-07、R-UI-14 等长期项。
- 本检查点新增完成：R-TEST-06 发送守卫纯逻辑面（部分）。
- 尚未开始：R-UI-08（state-split）、R-TEST-06 完整状态机/10 真实路径/14 v1-54/20 其余、R-TEST-03/04 剩余面、R-CI-07 阈值、R-SVC-05 任务期持锁面、R-SEC-03 CORS/绑定面、R-DB-04/05、R-BUILD-07、R-UI-14 等长期项。
- 本检查点新增完成：R-TEST-03 SecureKeyStore 透传面（部分）。
- 尚未开始：R-UI-08（state-split）、R-TEST-06 完整状态机/10 真实路径/14 v1-54/20 其余、R-TEST-03 Keystore 真机面/04 剩余面、R-CI-07 阈值、R-SVC-05 任务期持锁面、R-SEC-03 CORS/绑定面、R-DB-04/05、R-BUILD-07、R-UI-14 等长期项。
- 验证：assembleDebug + 三模块单测全绿。

## 第三批检查点 4（2026-08-06 续）

- 新增完成：R-AI-02/03/04/05/06、R-SEC-08、R-SVC-06/07、R-TEST-12、R-DOC-07、R-CI-08/09；R-SEC-03 JWT 密钥面、R-SVC-05 低电量面（部分）。
- 验证：assembleDebug + :ai/:memory/:app testDebugUnitTest 全绿（769 tests / 102 类 / 0 failures）；detekt、ktlintCheck、lintDebug（baseline）通过。
- 仍待 owner/后续批次：R-UI-08/R-UI-14、R-TEST-06/10/14/19/20、R-TEST-03/04 剩余面、R-CI-07 阈值、R-SEC-03 CORS/绑定面、R-SVC-05 任务期持锁面、R-DB-04/05、R-BUILD-07、R-BUILD-02（已有阻塞决策）。

## 第三批检查点 5（2026-08-06 续）

- 新增完成：R-TEST-19、R-TEST-14 v55→75、R-TEST-10 纯逻辑面、R-TEST-06 发送守卫面、R-TEST-20 OpenAI 图片请求体面（部分）。
- 验证：assembleDebug + :ai/:memory/:app testDebugUnitTest 全绿（785 tests / 106 类 / 0 failures）；detekt/ktlintCheck 通过。
- 仍待 owner/后续：R-UI-08/R-UI-14、R-TEST-03/04 剩余、R-TEST-06 完整状态机/10 真实路径/14 v1-54/20 其余、R-CI-07 阈值、R-SEC-03 CORS/绑定面、R-SVC-05 任务期持锁面、R-DB-04/05、R-BUILD-07、R-BUILD-02（已有阻塞决策）。

## 第三批检查点 8（2026-08-06 续）

- 新增完成：R-CI-07 debug-only Kover 阈值（ai 40 / memory 30 / app 12，`koverCachedVerifyDebug` 全绿）；R-CI-08 emulator job 改为 workflow_dispatch 手动触发；R-SEC-03 方案 B（默认 127.0.0.1 + CORS 仅本机，设置页局域网开关，WebServerAuthPolicyTest 6/6）；R-SVC-05 方案 B（仅生成任务期间持锁，设置页文案同步）；R-BUILD-02 按 owner 决策关闭。
- 验证：`:app:compileDebugKotlin` / `:app:testDebugUnitTest --tests WebServerAuthPolicyTest` / 三模块 `koverCachedVerifyDebug` / detekt / ktlintCheck 全绿；8 个 Lane 脚本 EXIT=0。
- 仍待 owner/后续：R-UI-08/R-UI-14 文件拆分、R-TEST-03/04 剩余、R-TEST-06 完整状态机/10 真实路径/14 v1-54/20 其余、R-DB-04/05、R-BUILD-07。

## 第三批检查点 9（2026-08-06 续）

- 新增完成：R-TEST-03/04 JVM 面。SecureKeyStore 拆出 SecureKeyCipher 接口 + AndroidKeyStoreCipher 默认实现 + delegate 注入（可 mock）；新增 SettingsSnapshotPolicy 备份安全键白名单/敏感片段策略；新增 SettingsSnapshotPolicyTest、BackupKeyExclusionTest（备份 JSON 扫描无 apiKey 明文）；SecureKeyStoreTest 3/3（含 delegate 替换）。
- 验证：`:app:testDebugUnitTest` 全绿；assembleDebug BUILD SUCCESSFUL；detekt/ktlintCheck 通过。真机 Keystore 硬件往返仍留待设备验证。
- 仍待 owner/后续：R-UI-08/R-UI-14 文件拆分、R-TEST-06 完整状态机/10 真实路径/14 v1-54/20 其余、R-DB-04/05、R-BUILD-07。

## 第三批检查点 10（2026-08-06 续）

- 新增完成：R-TEST-14 迁移链基础设施。MuseDbMigrationTest 改为反射收集全部已注册迁移（按 fromVersion 排序），insertLegacyRow 按 PRAGMA 动态补齐 NOT NULL 无默认值列；JVM 覆盖 v55→68 + 74→75。
- 阻塞边界：v1-54 无法在 Robolectric 覆盖：38→39 建 FTS4 vtable 报 vtable constructor failed；39-54 schema 快照存在迁移漂移（messages 缺索引/默认值），留真机验证。
- 验证：`:app:testDebugUnitTest --tests MuseDbMigrationTest` 全绿；detekt/ktlintCheck 通过。
- 仍待 owner/后续：R-UI-08/R-UI-14 文件拆分、R-TEST-06 完整状态机/10 真实路径/20 其余、R-DB-04/05、R-BUILD-07。

## 第三批检查点 11（2026-08-06 续）

- 新增完成：R-UI-14 首个文件拆分（DebugScreen）。纯搬运拆出 DebugCrashLogSheet.kt、DebugAnalyticsSheet.kt、DebugFormatters.kt；DebugScreen 从 1572 行降到约 940 行；仅调整 private→internal/文件级 Suppress，无行为变化。
- 验证：assembleDebug + :ai/:memory/:app testDebugUnitTest 全绿；detekt/ktlintCheck 通过。
- 仍待 owner/后续：R-UI-14 其余大文件（ChatScreen/MessageBubble/MemoryScreen/GroupChatDetailScreen/ProviderSection/ChatViewModel/MuseDb/GroupChatScheduler 等）、R-TEST-06 完整状态机/10 真实路径/20 其余、R-DB-04/05、R-BUILD-07。

## 第三批检查点 7（2026-08-06 续）

- 新增完成：R-CI-05 转阻断。清掉 check_engineering_discipline.py 9 个 empty_catch（JsSandbox 注入 JS 8 处改为 console.debug 记录，GreetingHelper 1 处改 runCatching + Logger.w）；SettingsSubPages 剪贴板标签改 stringResource（1 处新增 CJK）；ChatScreen 固定 22sp 加有意豁免注释（1 处 fontSize）；CJK baseline 收紧到 463 处；ci.yml Lane checks 移除 continue-on-error: true。
- 验证：8 个 Lane 脚本 EXIT=0；ci/test 3 个脚本 15+20+1 全绿；assembleDebug + :app:testDebugUnitTest BUILD SUCCESSFUL；detekt + ktlintCheck BUILD SUCCESSFUL。
- 仍待 owner/后续：R-UI-08/R-UI-14、R-TEST-03/04 剩余、R-TEST-06 完整状态机/10 真实路径/14 v1-54/20 其余、R-CI-07 阈值、R-SEC-03 CORS/绑定面、R-SVC-05 任务期持锁面、R-DB-04/05、R-BUILD-07、R-BUILD-02（已有阻塞决策）。
