# Muse 全链路穷尽式审查 · 总报告（2026-10-10/11）

审查基线：HEAD `24bf247f`（v2.5.8 之后一个提交，工作树干净）。
方法：10 个链路子代理并行审查 + 主审横切自审，全部发现带 文件:行号+代码证据，
对照 `.audit/AUDIT_FRAMEWORK.md` 与 9 份链路去重基线去重（历史已修项/已知项/设计取舍不报）。
每份分报告在 `.audit/REPORT_*.md`；关键发现由主审逐一复核过源码（复核结论已修正个别误报）。

## 总量

| 链路 | P0 | P1 | P2/P3 | 小计 |
|---|---|---|---|---|
| 数据/备份/迁移 | 0 | 5 | 10 | 15 |
| UI/设计系统/可访问性 | (1→P1,复核改判) | 6+1 | 9 | 16 |
| AI Provider/流式/工具循环 | 0 | 4 | 9 | 13 |
| 文档/测试/开源健康 | 0 | 8 | 5 | 13 |
| 性能/稳定性 | 0 | 5 | 7 | 12 |
| 构建/CI/发布 | 0 | 3 | 9 | 12 |
| 记忆/RAG | 0 | 6 | 6 | 12 |
| 渠道/后台可靠性 | 0 | 2 | 9+3备注 | 11 |
| Agent 设备控制/运行时 | 0 | 5 | 5 | 10 |
| 安全与权限 | 0 | 1 | 7 | 8 |
| X 横切（vdserver/无障碍桥，主审自审） | 0 | 2 | 1 | 3 |
| **合计** | **0** | **47** | **~75** | **~125** |

## 十大优先修复（主审复核过证据，按影响排序）

1. **知识库文件夹在备份链路四路径全失效**（数据 F1/F2，P1 最高优先——用户数据丢失）
   - NDJSON 导入缺 `"knowledgeFolder"` 分支（BackupService.kt:1015-1384 无 case、无缓冲）而清空阶段 `:988` 删光；
   - `buildBackup()` 读到 folders（`:2268`）但 `Backup(...)` 构造漏传（`:2326-2372`）→ 单 JSON 导出丢 + recovery point 恒空；
   - **恢复失败回滚 = deleteAll + 插入空列表（`:2622`/`:2663`）→ 一次回滚清空全部文件夹**；
   - B-34 对账只数解析行数（`:1007-1013`）不验入库，静默丢失。
2. **A2A 远程入口 × 全局放行位 = 静默执行 HIGH 设备工具**（Agent F1，P1 安全）
   - `ChatToolApprovalCoordinator.kt:58-62`：进程级 `appRunAllowAllTools`（任意审批卡勾一次）→ TRUSTED →
     `ToolPermissionResolver.kt:147-149` 直接 Auto（无 Pending、无 30s 兜底）；
   - A2A `/a2a` 仅 JWT、无焦点校验 → 用户勾过一次后，远程可静默 `device_shell`/`app_clear_data`/`ui_agent`。
3. **A2A 复用全局 ChatViewModel 单例**（渠道 F1、AI F4、数据 F4、Agent F5、性能 F3/F4、安全 F2/F3 六链路交叉印证，P1）
   - `AppChatModule.kt:16-19` ChatViewModel 是应用级 single；`WebServer.kt:117` 注入同一实例；
   - A2A `switchSession` 劫持用户前台会话、`stop()` 停用户生成、并发互踩、`cancelTask` 无 taskId 校验；
   - 修复方向（各链路一致）：A2A 走独立无 UI 生成路径（同构 `TaskerTriggerReceiver` 的 `ChatService.completeText`
     单轮模型）或最低限度 per-handler Mutex + taskId→sessionId 映射 + 焦点仲裁。
4. **lanes 门禁在 HEAD 提交态实测红**（CI F1/F2，P1 流程失守）
   - hardcoded_cjk：S1-S5 拆分与诊断导出留下 9 处基线外新增（ChatToolLoopRunner +6 / ChatToolResumeCoordinator +2 /
     SettingsScreen 69>68），基线止于 09-29；exit=1 实测；
   - strict locale：values-en 缺 `chat_token_estimated_cost`（主审 grep 复核 0 命中），一行修复。
5. **官网仓库凭据已入 git 历史**（文档 F10a，P1 安全卫生）
   - `museai.ltd\DEPLOY.md:3-4` 被 git 跟踪（commit `839dfb3`），含 `root@117.72.212.102` 与 SSH 私钥本机路径；
   - 该仓库当前无远端（无实际外泄），但任何未来误加远端即泄露；建议 `git rm --cached` + `.gitignore` + 服务器侧轮换。
6. **五平台接收器"永久死亡无自愈"**（渠道 F2，P1）
   - `TelegramReceiver.kt:58` loadOffset 在 try 外抛错即 job 终结；五接收器同模式 `config == null → return`；
   - `ChannelStore.load():27-29` 失败静默空列表 → 全部循环安静退出，无 WorkManager 兜底。
7. **Tasker 广播无鉴权/频控/长度上限**（安全 F1，P1）
   - `AndroidManifest.xml:263-269` exported=true 无 permission（主审复核）；任意应用循环触发计费 LLM；
   - 缓解成立（completeText 不带 tools，无法间接操作设备）；叠加性能 F2：goAsync 10s 窗口跑 120s LLM。
8. **supersede 续传时序缺陷**（AI F1，P1——"首字被吞"相邻新路径）
   - `ChatToolLoopRunner.kt:869-871` 只重置 `lastUiUpdateChars`，`lastPersistChars/lastNotifChars` 滞留（主审复核）；
   - 新内容比旧短时节流失效，进程被杀后 DB 残留旧文+新文拼接。
9. **vdserver 全锁 + shell 无超时**（X1，P1）
   - 五个 Binder 入口同一把 `synchronized`；`execShellCapture`/`resolveLauncherComponent` 的 `waitFor()` 无超时
     （ServerService.java:361/377）；`am start` 挂起拖死全部虚拟屏操作，空闲看门狗也被 touch() 喂着不退出。
10. **ensureIndexed 绕过 embedding 指纹**（RAG F1，P1）
    - `BuiltInKnowledgeDocSeeder.kt:108-113` 只看 chunkCount/contentHash，不比对 embeddingModel key；
    - 同维度模型切换后 internal 文档新旧向量混存，检索质量静默劣化。

## 次级 P1 快览（47 条中的其余 37 条）

- **A2A 协议实现**：GetTask/CancelTask 误用 JSON-RPC id 当 taskId（安全 F2、文档 F2 双链路独立发现）；
  INVALID_REQUEST 死码；sendMessage 无互斥（AI F4）。
- **UI 拆分回归**：新代码回归"丢更新"覆盖模式（UI F2，`ChatToolResumeCoordinator:194/235` 等，CAS accessor 已存在未用——
  主审抽样属实）；`refreshDisplayedSessionFromDatabase:4601` 未接 SessionSwitchGuard（UI F3，串会话同型）；
  审批卡切回"复活"与后台审批超时不可见（UI P0 复核降级后的残留，主审改判 P1）。
- **RAG**：guess 维度污染 HNSW 指纹每次冷启动重建（F3）；三路注入无总量闸门（F5）；删 KB 逐文档全量写盘（F4）；
  search_conversation 无条目级截断（F6）；ensureIndexed 失败无退避熔断（F2）；墓碑子串误删（F7）。
- **性能**：诊断导出主线程 zip（F1，主审复核）；每轮收尾全量 DB 读 4/5 浪费（F5）；suspendCancellableCoroutine
  无取消释放（F3）；全局 isStreaming 判定完成（F4）。
- **自动化**：workflow displayId lease 校验盲区可劫持 Agent 专用虚拟屏（F2）；DeviceCapabilityHook 持锁跑无超时
  su 探测可冻结节面（F3）；patchelf 产物无 CI 护栏（F4）。
- **CI**：tag↔Gradle 默认版本三版未同步（F3）；detekt cap 32 条无账空间（F5）；horizontal_inset 唯一 fail-open 孤例（F6）；
  .gitattributes 未锁 Kotlin 行尾（F7，CRLF 噪音根因）。
- **文档/测试**：A2A/Tasker/插件 Hook/搜索 13 家/诊断红线组合级测试全零（F1/F2/F3/F4/F6）；
  devdocs 与代码漂移（13 家 vs "三种"）；NOTICE 缺 v2.5.x 新依赖；5 语言包 101→118。
- **X 横切**：ui_screenshot 跨 UID 写主应用 cacheDir 必失败 + path 无白名单（X2）；三层通道截图未接 Provider 双路（X3）。
- 渠道：QQ WS 无 RESUME 丢事件、三存储恢复失败静默清零、WeClaw/钉钉解密失败无限 Retry、
  ON_STOP 停接收器与退避抖动核实均未落地。

## 复核改判记录（主审验证后的修正）

- UI 链路报的 **P0（审批 deferred 悬挂）降级为 P1**：`ChatViewModel.kt:4209` `remove()?.complete(Denied)` 实际会
  complete deferred，30s 超时兜底也在；真实残留是"审批卡复活"与后台超时不可见两个语义缺陷。
- AI 链路 FloorContextLimiterHook（AI F3）"孤儿 tool_result"部分为条件成立（截断点恰好落在
  assistant(toolCalls) 与 TOOL 之间）；SYSTEM 块前置破坏位置语义为直接证据。
- 基线缺口闭环确认：Gemini usage-only terminal、Anthropic/Gemini abort listener、BM25 中文 2-gram
  （历史"静默失效"已修且有回归钉）。

## 建议的行动批次

1. **止血（半天）**：CI F2 补一行英文翻译 + CI F1 刷新 cjk 基线 → lanes 恢复全绿；Data F1/F2 补
   knowledgeFolder 导入分支 + buildBackup 传参（四行级修复）+ 补"导出→恢复→文件夹数不变"回归。
2. **安全（1 天）**：Agent F1（A2A 不继承 appRunAllowAllTools，或 A2A 会话强制独立审批模式）；
   安全 F1（Tasker 加开关+限频+长度上限）；文档 F10a（凭据移出跟踪 + 轮换）。
3. **A2A 专项（1-2 天）**：独立生成路径（参照 Tasker 单轮模型）一次性解决六链路交叉发现的会话劫持/
   并发串台/cancel 误伤/焦点仲裁/task id 语义，补协议测试。
4. **可靠性（2-3 天）**：渠道 F2 自愈、AI F1 supersede 游标、X1 vdserver 超时、UI F2/F3 CAS+guard 统一。
5. **台账收尾**：detekt cap、NOTICE、devdocs 漂移、.gitattributes 行尾、RAG F1 指纹。
