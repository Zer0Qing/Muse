# 审计修复执行清单（2026-08-16 报告 → 修复）

> 对应报告：`E:\1Project\Muse\深度审计报告_2026-08-16.md`（94 项）
> 规则：所有改动只留本地，不上传 GitHub；每批完成 assembleDebug + 相关单测验证
> 状态图例：⬜ 未开始 / 🔧 进行中 / ✅ 已修复已编译 / 🧪 已修复+测试通过 / ⏸ 已评估不改（有意行为）

## S 级（6）

| 编号 | 问题 | 涉及文件 | 状态 |
|---|---|---|---|
| S-01 | 媒体字段多轮工具循环不落库 | ChatViewModel.kt / ToolOrchestrator.kt | 🧪 已修复(上一 AI 完成主体:persistToolMessageMedia + mergedMedia;本 AI 清 debug 日志、修编译;待回归) |
| S-02 | generate_video 不渲染不落库 | ChatViewModel.kt / MessageBubble.kt / MessageEntity.kt / SessionRepository.kt / MuseDb.kt | 🧪 已修复(上一 AI 完成:v88 迁移 + videoFileUri 往返 + AssistantVideoCard;本 AI 补 7 语言 chat_generated_video_cd 字符串、修编译) |
| S-03 | 置顶记忆双存储分裂 | SystemPromptAssembler.kt / PinnedMemoryStore.kt | 🧪 已修复(统一读 PinnedMemoryStore + 旧 pinned_memories.json 一次性迁移;Koin 注入) |
| S-04 | 删除记忆不生效/复活 | MemoryViewModel.kt / MemoryCompiler.kt / FactStore.kt | 🧪 已修复(删除墓碑 fact_tombstones.json + compileFacts/compileToday 三路过滤 + purgeTombstonedFacts 立即生效) |
| S-05 | 记忆管道明文外发 PII | SessionSummaryManager.kt / MemoryAutoSaveScheduler.kt / PiiGuard.kt | 🧪 已修复(PiiGuard.mask/unmask 可逆掩码,rolling summary 与 autoSave 输入外发前掩码) |
| S-06 | channel_read_context 悄悄话泄漏 | GroupChatScheduler.kt | 🧪 已修复(contextProvider 走 visibleMessagesFor 过滤) |

## A 级（20）

| 编号 | 问题 | 状态 |
|---|---|---|
| A-01 | 并行媒体工具互相覆盖 | ⬜ |
| A-02 | toggle 三件套参数矛盾 | ⬜ |
| A-03 | set_brightness auto 未实现 | ⬜ |
| A-04 | generate_image 定义/执行分裂 | ⬜ |
| A-05 | read_file 双实现 | ⬜ |
| A-06 | channel_reply skill 版伪造身份 | ⬜ |
| A-07 | 卡死/早停无收尾消息 | ⬜ |
| A-08 | guaranteedSend 热循环 | ⬜ |
| A-09 | 群聊记忆跨群串台 | ⬜ |
| A-10 | subagent 结果丢失（parent_session_id） | ⬜ |
| A-11 | whisper 线程 id 复用失效 | ⬜ |
| A-12 | persistInterruptedAssistant fallback | ⬜ |
| A-13 | toolAssistantId 全局共享 | ⬜ |
| A-14 | [已中断] 误标错误消息 | ⬜ |
| A-15 | RichInputBar 格式工具条死代码 | ⬜ |
| A-16 | 生图收尾零测试 | ⬜ |
| A-17 | CI release job 必失败 | ⬜ |
| A-18 | memory PiiGuard 姓名误伤 | ⬜ |
| A-19 | 跨助手记忆污染 | ⬜ |
| A-20 | 双 PiiGuard 规则漂移 | ⬜ |

## B 级（32） / C 级（36）

见报告正文对应编号（B-01..B-32 / C-01..C-36）。执行顺序：核心链路优先，按文件分组批处理。
