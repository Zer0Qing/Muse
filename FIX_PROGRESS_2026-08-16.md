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
| A-01 | 并行媒体工具互相覆盖 | 🧪 已修复(exec* 媒体 append+去重;mergeAssistantContent 幂等;StateFlow CAS 并发安全) |
| A-02 | toggle 三件套参数矛盾 | 🧪 已修复(resolveToggleAction 兼容 enabled/action;删除翻转承诺;手电筒 status 如实跟踪) |
| A-03 | set_brightness auto 未实现 | 🧪 已修复(auto 分支写 SCREEN_BRIGHTNESS_MODE;required 去 value;7 语言新字符串) |
| A-04 | generate_image 定义/执行分裂 | 🧪 已修复(两处 skillMap 构建剔除与本地工具同名 skill,执行路由统一走本地实现) |
| A-05 | read_file 双实现 | 🧪 已修复(同上,同名 skill 剔除;description 与本地实现对齐) |
| A-06 | channel_reply skill 版伪造身份 | 🧪 已修复(主会话 skillMap 剔除 channel_* 三件套) |
| A-07 | 卡死/早停无收尾消息 | 🧪 已修复(abortReason 区分卡死/连续失败/轮次耗尽文案;收尾替换语义) |
| A-08 | guaranteedSend 热循环 | 🧪 已修复(配置新增 lastFailedAt;失败退避一个 baseInterval;成功清零) |
| A-09 | 群聊记忆跨群串台 | 🧪 已修复(getByAssistantAndChat DAO;buildGroupChatMemorySection(assistantId, chatId);单聊不注入) |
| A-10 | subagent 结果丢失（parent_session_id） | 🧪 已修复(DeferredResultStore.consumeUnowned + ChatViewModel 降级注入当前会话 + ERROR 告警;工具描述强调必填) |
| A-11 | whisper 线程 id 复用失效 | 🧪 已修复(SAFE_THREAD_ID 放行 ':' 并放宽到 128 字符) |
| A-12 | persistInterruptedAssistant fallback | 🧪 已修复(只接受 partialMsg 或 expectedAssistantId 命中;空白内容跳过) |
| A-13 | toolAssistantId 全局共享 | 🧪 已修复(代际令牌 toolGenerationToken;exec* 写媒体前校验;取消/收尾失效) |
| A-14 | [已中断] 误标错误消息 | 🧪 已修复(streamRound 入口同步 state.currentAssistantId;限定本轮消息 id) |
| A-15 | RichInputBar 格式工具条死代码 | 🧪 已修复(visible 直接取 formatEnabled;移除死状态) |
| A-16 | 生图收尾零测试 | 🧪 已修复(mergeFinalAssistantMedia 抽 internal 纯函数 + ChatMediaMergeTest 5 例) |
| A-17 | CI release job 必失败 | 🧪 已修复(ci.yml 注入 -PversionName/-PversionCode;storeFile 绝对路径) |
| A-18 | memory PiiGuard 姓名误伤 | 🧪 已修复(称谓白名单正则 + 上下文模式;白名单扩至 ~200 词;6 新用例) |
| A-19 | 跨助手记忆污染 | 🧪 已修复(getInRange 加 mainAssistantId 过滤;compileToday/compileFacts 传主助手 id;保留空/旧行) |
| A-20 | 双 PiiGuard 规则漂移 | 🧪 已修复(CREDIT_CARD 扩至 16-19 位;ID_CARD 规则前置防身份证误判;app/memory 各 2 新用例) |

## B 级（32） / C 级（36）

见报告正文对应编号（B-01..B-32 / C-01..C-36）。执行顺序：核心链路优先，按文件分组批处理。

### B 级进度

- ✅ B-01(收尾): b64/url 只接受字符串标量(数字/布尔/null 全过滤) + url 前缀校验(http/data:image/);ImageResponseParseTest +3 例
- ✅ B-02: SmartImage 解码前剥离换行(兼容 76 字符换行 base64)
- ✅ B-04: 收尾消息注入改替换语义(同 id 不重复渲染)
- ✅ B-05: execGenerateVideo reference_images data URI 不再被逗号拆坏
- ✅ B-08: schedule_reminder 示例改可解析格式(ISO 需带时区偏移)
- ✅ B-10: 评分阈值 0.2→0.35(最小评分 0.2 不再恒过线,"防骚扰"门槛恢复)
- ✅ B-13: cron dom+dow 双受限改 OR 语义(Quartz 规范)+ 2 新用例
- ✅ B-17: FactStore.update 强制 PII 脱敏(LLM 路径);tags 逐项脱敏
- ✅ B-18: expires_at 过期过滤(getByScope/getBySpace/getByScopeAndSpace/getAll)+ applyDecay 删除过期
- ✅ B-26: 切会话日志风暴降级为 debug(持久化有兜底)
- ✅ B-28: OpenAIProvider 7 处 URL 日志脱敏(去 query 参数)
- ✅ B-31: /reset 流式中拒绝时提示独立文案(7 语言)
- ⬜ B-03/B-06/B-07/B-09/B-11/B-12/B-14/B-15/B-16/B-19/B-20/B-21/B-22/B-23/B-24/B-25/B-27/B-29/B-30/B-32

### C 级进度

- ✅ C-01(部分): 多工具描述与实现对齐(上一 AI)+ A-02/A-03 实现级修复补全
- ✅ C-17: 三岛尺寸按用户反馈定为 40/48/40(与审计建议相悖,用户反馈优先,记录在案)
- ✅ C-20(部分): 头像持久化返回纯路径(去 file:// 前缀);唯一文件名/大小上限未做(留待)
- ✅ C-34: 版本默认值 162/1.0.62 → 175/1.0.75
- ⬜ C-02~C-16/C-18/C-19/C-21~C-33/C-35/C-36
