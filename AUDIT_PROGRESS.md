# AUDIT_PROGRESS.md — Muse 改造清单执行进度

> 执行基线：任务书 `E:\1Project\Muse\Muse项目全面审计与改造清单.md` v1.1（2026-08-06 所有者审核修订版）
> 仓库：`E:\1Project\Muse\1muse`，分支：`refactor-audit-fixes`（基于 main f967fda）
> 批次定义以任务书「修订说明 → 执行顺序」为准。R-SEC-01 / R-DB-01 / R-BUILD-01 / R-CI-01 / R-AI-01 已驳回或降级，不执行。
> 本文件在仓库根目录维护；上下文交接卡同步维护于 `E:\1Project\Muse\上下文交接卡.md`。

## 批次状态

- [x] 第一批（止血）：R-DB-02 + R-UI-01 / R-SEC-02 / R-SEC-06 / R-UI-03 / R-DOC-01 / R-DOC-05
- [ ] 第二批（本迭代）：R-TEST-01 / R-TEST-05 / R-TEST-09 / R-SVC-04 / R-BUILD-04 / R-BUILD-02 / R-UI-02（后端面）
- [ ] 第三批及长期：R-UI-04~09、R-SVC-01~03、R-BUILD-05/08/09/10、R-TEST 其余、R-CI 其余（R-CI-01 除外）、R-DOC 其余，以及长期项按依赖顺序推进

## 条目明细（编号 | 状态 | 验证结果 | 备注）

| 编号 | 状态 | 验证结果 | 备注 |
|---|---|---|---|
| R-DB-02 | 已完成 | `:app:testDebugUnitTest` 全绿；`:app:testDebugUnitTest --tests "*KnowledgeChunkFtsSelfHealTest*"` 2/2 通过 | onOpen 改 sqlite_master 探测 + 清影子表重建 + DAO 单写自愈 + RagService 批量自愈；新增 KnowledgeChunkFtsSelfHealer |
| R-UI-01 | 已完成 | `assembleDebug` 零错误；KnowledgeScreen 编译通过 | 新增修复/重建索引按钮、进度与多语言 Toast；RagService.repairKnowledgeFtsIndex 提供后端入口 |
| R-SEC-02 | 已完成 | grep 确认 McpOAuthFlow 无 `body.take(...)` token 日志 | 三处日志只保留 HTTP 状态码/通用文案 |
| R-SEC-06 | 已完成 | `:ai:testDebugUnitTest` 全绿；`take(500)` 在 ai 模块 0 命中 | 400 日志改为 model/messages/tools/bodyBytes/SHA-256 摘要 |
| R-UI-03 | 已完成 | 编译通过；grep `warm_paper` 代码引用已清理 | AppearanceSettingsStore.DEFAULT_THEME_ID="mono" 三处统一；软件功能.md §7.1 同步 |
| R-DOC-01 | 已完成 | `:memory:testDebugUnitTest --tests "*FactDbMigrationTest*"` 通过 | 删除两个 .db；.gitignore 加 *.db；FactDbMigrationTest 用临时目录；hygiene 脚本禁止 .db |
| R-DOC-05 | 已完成 | 编译通过 | ai/build.gradle.kts 注释优先级改为 -P > 环境变量 > local.properties |
| R-TEST-23 | 已完成（随 R-DB-02） | 2/2 通过 | app 模块 Robolectric 的 SQLite 不支持 FTS4 vtable，测试用真实 DAO 默认包装 + fake raw 实现锁定自愈逻辑；真实建表自愈需真机回归 |

## 第一批小结

- 处理条目：R-DB-02、R-UI-01、R-SEC-02、R-SEC-06、R-UI-03、R-DOC-01、R-DOC-05，并随 R-DB-02 补 R-TEST-23 回归测试。
- 验证：`:ai:testDebugUnitTest` / `:memory:testDebugUnitTest` / `:app:testDebugUnitTest` 全绿；`assembleDebug` BUILD SUCCESSFUL。
- 跳过的条目：R-SEC-01 / R-DB-01 / R-BUILD-01 / R-CI-01 / R-AI-01（修订版驳回/降级/待确认，不执行）。
- 对外接口：无已 import 类名/函数签名变更；无 DB schema、Room 列名、DataStore key、JSON 字段、strings key 变更；新增了 DAO raw 方法、SelfHealer 对象、RagService.repairKnowledgeFtsIndex、KnowledgeScreen 修复入口与字符串资源。
- 遇到的问题：app 模块 Robolectric 的 SQLite 无法创建 FTS4 vtable（`vtable constructor failed`），与 memory 模块测试环境表现不一致；R-TEST-23 因此改为纯 JVM fake DAO 测试真实自愈包装，真实建表/真机路径需在设备回归（用户日志场景）。

## 遇到的问题（阻塞项记录，供所有者 review）

- 非阻塞：app 模块 Robolectric 不支持 FTS4 vtable，R-TEST-23 采用 fake DAO 锁定自愈包装逻辑；R-DB-02 的“真机导入猫咪品种大全.md.txt 索引成功”验收需真机回归。
