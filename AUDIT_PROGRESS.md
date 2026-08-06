# AUDIT_PROGRESS.md — Muse 改造清单执行进度

> 执行基线：任务书 `E:\1Project\Muse\Muse项目全面审计与改造清单.md` v1.1（2026-08-06 所有者审核修订版）
> 仓库：`E:\1Project\Muse\1muse`，分支：`refactor-audit-fixes`（基于 main f967fda）
> 批次定义以任务书「修订说明 → 执行顺序」为准。R-SEC-01 / R-DB-01 / R-BUILD-01 / R-CI-01 / R-AI-01 已驳回或降级，不执行。
> 本文件在仓库根目录维护；上下文交接卡同步维护于 `E:\1Project\Muse\上下文交接卡.md`。

## 批次状态

- [x] 第一批（止血）：R-DB-02 + R-UI-01 / R-SEC-02 / R-SEC-06 / R-UI-03 / R-DOC-01 / R-DOC-05
- [ ] 第二批（本迭代）：R-TEST-01 / R-TEST-05 / R-TEST-09 / R-SVC-04 / R-BUILD-04 / R-BUILD-02 / R-UI-02（后端面）
  - 已完成并验证：R-TEST-01 / R-TEST-05 / R-TEST-09 / R-SVC-04 / R-BUILD-04 / R-UI-02（后端面）
  - 阻塞待 owner：R-BUILD-02（material3 1.4.0 stable 的 Expressive API 为 internal，无法直接升级）
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
| R-TEST-01 | 已完成 | `:ai:testDebugUnitTest --tests "*StreamGuardTest*"` 3/3 通过 | guard 挂起/reasoning 先到/空 finishReason；早停回退网络路径由 FirstEventWatchdogTest + 快照测试共同覆盖 |
| R-TEST-05 | 已完成 | `:ai:testDebugUnitTest --tests "*ProviderRequestBodySnapshotTest*"` 3/3 通过 | Anthropic/Gemini/Ollama(OpenAI 兼容)请求体快照 |
| R-TEST-09 | 已完成 | memory/app PiiGuardTest 全绿 | 手机(含空格)/邮箱/15/18 位身份证 mask/unmask 往返；两处 PiiGuard 正则同步增强 |
| R-TEST-02 | 已完成（随 R-UI-02） | `ChatViewModelFocusRestoreTest` 2/2 通过 | 进程恢复优先还原查看会话；outbox 重放不改写焦点 |
| R-SVC-04 | 已完成 | 编译通过 | JsSandbox 超时销毁 WebView、连续超时熔断、总超时配额、插件自动禁用 |
| R-BUILD-04 | 已完成 | `assembleDebug` 成功；grep 无 haze/sonner | 删除死依赖声明与实现 |
| R-BUILD-02 | 阻塞待 owner | 当前保持 material3 1.4.0-alpha04 + BOM 2024.12.01，`assembleDebug` 成功 | 实测 material3 1.4.0 stable 的 `MaterialExpressiveTheme`/`MotionScheme`/`ExperimentalMaterial3ExpressiveApi` 均为 internal，且 BOM 2026.06.01 会强制覆盖 alpha04；直接升级无法编译，切换标准 MaterialTheme 会丢失 expressive 动效 |
| R-UI-02 | 已完成 | R-TEST-02 2/2 通过；编译通过 | 新增 viewed_session_id / generating_session_id 两个 DataStore key；ChatViewModel 启动恢复查看焦点；outbox/checkpoint 重放不改写焦点；docs 更新 |

## 第二批小结（检查点）

- 已完成：R-TEST-01、R-TEST-05、R-TEST-09、R-TEST-02、R-SVC-04、R-BUILD-04、R-UI-02 后端面。
- 验证：`:ai:testDebugUnitTest` / `:memory:testDebugUnitTest` / `:app:testDebugUnitTest` 全绿；`assembleDebug` BUILD SUCCESSFUL。
- 阻塞：R-BUILD-02 无法按任务书直接切 material3 stable，需要 owner 决策（保持 alpha04 等待 API 公开 / 接受标准 MaterialTheme 的动效变化 / 提供独立 expressive artifact）。
- 对外接口：无已 import 类名/函数签名变更；新增 SettingsRepository 会话焦点存取方法、JsSandbox 熔断 API、DAO raw 方法、测试类；无 DB schema/Room 列名/strings key 变更。

## 遇到的问题（阻塞项记录，供所有者 review）

- 阻塞（需要 owner 决策）：R-BUILD-02。material3 1.4.0 stable（BOM 2026.06.01）中 `MaterialExpressiveTheme`、`MotionScheme`、`ExperimentalMaterial3ExpressiveApi` 均为 internal；BOM 还会强制覆盖 1.4.0-alpha04。已实测 `assembleDebug` 编译失败，回退到 alpha04 + BOM 2024.12.01 后全绿。
- 非阻塞：app 模块 Robolectric 不支持 FTS4 vtable，R-TEST-23 用 fake DAO；R-TEST-01 的早停回退完整网络路径在 MockWebServer 下不稳定，改由 FirstEventWatchdogTest + ProviderRequestBodySnapshotTest 覆盖。
- 第二批其余条目均已实现并通过验证，等待 owner 对 R-BUILD-02 的决策后即可收尾第二批。

## 第三批检查点（进行中）

- 已完成：R-UI-04（主题页多语言）、R-UI-05（TimePicker 分钟设置）、R-UI-06（封面 prompt + 空输出降级）、R-UI-07（TTS 失败 Toast）、R-UI-09（TaskCard 取消 + 确认 ANR 后台过滤/429 文案已存在）、R-SVC-01（NodeInfo recycle + 非主线程断言）、R-SVC-02（截图反射失败提示）、R-SVC-03（Shizuku suspend/超时/release）、R-BUILD-05（biometric 1.1.0 stable）、R-BUILD-08（KSP 2.3.11）、R-BUILD-09（accessibility 纳入 Kover）、R-BUILD-10（ProGuard 部分收窄）。
- 尚未开始：R-UI-08（state-split）、R-TEST 其余、R-CI 其余、R-DOC 其余、长期项。
- 验证：assembleDebug + 三模块单测全绿。
