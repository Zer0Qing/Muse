# Muse 外部引用清理进度

最后更新：2026-08-05

## 批次状态
- [x] P0 核心实现重写（8/8 文件）
- [x] P1 免费模型机制（6/6）
- [x] P2 memory 模块（11 重写 + 注释清理）
- [x] P3 ai/app 注释清理
- [x] P4 strings + 文档
- [x] P5 全量复查

## 文件明细
| 文件 | 处理方式 | 状态 | 备注 |
|------|----------|------|------|
| ai/.../registry/ModelRegistry.kt | 重写 | ✅ 完成 | 保留公开查询入口，内部匹配函数重组 |
| ai/.../registry/ModelRegistryDsl.kt | 重写 | ✅ 完成 | DSL 规则引擎内部重命名/重组 |
| ai/.../util/KeyRoulette.kt | 重写 | ✅ 完成 | LRU + 黑名单逻辑重组 |
| app/.../ai/GenerationHandler.kt | 重写 | ✅ 完成 | 多步工具循环拆为独立状态机 |
| app/.../tools/ToolApprovalState.kt | 重写 | ✅ 完成 | 状态语义自述 |
| app/.../tools/ToolConfigStore.kt | 重写 | ✅ 完成 | 读写路径重组，key/JSON 结构不变 |
| app/.../session/ConversationSessionManager.kt | 重写 | ✅ 完成 | 抽出 idle 清理器 |
| material3/.../DynamicSchemeExt.kt | 重写 | ✅ 完成 | 明暗色映射拆为两个私有构造路径 |
| ToolOrchestrator / PendingToolCallStore / DebugLogStore / DebugScreen / McpConfig / AssistantRegex / CharacterCard* / SillyTavernCard / JsSandbox / asr/* / S3Client / WebDavClient / CloudBackupScheduler / UIMessage / SafeModeScreen / MuseDb / AssistantEntity / ChatViewModel / mcp/* | 注释清理 | ✅ 完成 | 外部引用与行号引用全部清除 |
| ai/.../core/FreeModelConfig.kt | 重写 | ✅ 完成 | fallback 策略重组，常量与函数签名不变 |
| ai/.../core/ProviderHttpSupport.kt | 重写 | ✅ 完成 | key 选择流程整理，protected API 不变 |
| ai/.../openai/OpenAIProvider.kt | 注释清理 | ✅ 完成 | 全部外部引用/归因注释改写 |
| app/.../data/preset/PresetProviders.kt | 注释清理 | ✅ 完成 | 供应商规格注释自述 |
| app/.../ui/HtmlPreviewScreen.kt | 注释清理 | ✅ 完成 | WebView 预览实现自述 |
| ai/.../core/ModelListCache.kt | 注释清理 | ✅ 完成 | 缓存设计自述 |
| memory/.../pii/PiiGuard.kt | 重写 | ✅ 完成 | 规则改为枚举数据驱动，正则写法重组 |
| memory/.../prompt/FactExtractionPrompt.kt | 重写 | ✅ 完成 | 提示词全文重写，示例更换 |
| memory/.../prompt/CompilePrompts.kt | 重写 | ✅ 完成 | 四块提示词重写，语义约束保留 |
| memory/.../prompt/RollingSummaryPrompt.kt | 重写 | ✅ 完成 | system prompt 重写 |
| memory/.../format/RollingSummaryFormat.kt | 重写 | ✅ 完成 | 文案重写，标题契约不变 |
| memory/.../fact/FactStore.kt | 局部重写 | ✅ 完成 | 标签/FTS 查询层重组，自研逻辑保留 |
| memory/.../budget/LlmBudget.kt | 局部重写 | ✅ 完成 | 推理缓冲 API 改名并同步调用方/测试 |
| memory/.../time/TimeContext.kt | 局部重写 | ✅ 完成 | 函数改名并同步调用方，04:00 切日保留 |
| memory/.../pin/PinnedMemoryStore.kt | 局部重写 | ✅ 完成 | 双文件合并 + 更新时间优先级 |
| memory/.../deep/DeepMemoryProcessor.kt | 局部重写 | ✅ 完成 | 解析辅助重组，dirty 管线语义保留 |
| memory/.../compile/MemoryCompiler.kt | 局部重写 | ✅ 完成 | 编译管线内部函数改名，指纹语义不变 |
| memory P2-C 注释文件 | 注释清理 | ✅ 完成 | 全部 memory 模块外部引用清除 |
| ai/app P3 注释文件（约 170 文件） | 注释清理 | ✅ 完成 | 外部引用注释全部改写为功能自述 |
| Model.kt / ModelRegistryDsl.kt / VisionBridge.kt | 特殊项 | ✅ 完成 | 坐标格式名改为 muse-box，旧值 hanako 读兼容 |
| privacy/PiiGuard.kt / SafetyPolicy.kt | 注释清理 | ✅ 完成 | 规则实现自述 |
| strings_data / strings_features / strings_notif_schedule（7 语言） | XML 注释清理 | ✅ 完成 | 仅改注释行，string 内容未动 |
| docs/SKILLPKG.md | 文档清理 | ✅ 完成 | 已本地修改（该文件被 .gitignore 忽略，未入库） |

## P5 小结（2026-08-05）
- 验收 grep：非允许残留 0 行（仅剩数据导入产品文案、日文“参照”产品文案）
- 全量编译：通过（`assembleDebug`）
- 单测：通过（`:ai:testDebugUnitTest :memory:testDebugUnitTest :app:testDebugUnitTest`）
- 跳过/阻塞项：无
- 对外接口变更：无新增（P2 改名已记录）
- 任务状态：全部批次完成，等待所有者 review

## 遇到的问题（阻塞项）
- 暂无
