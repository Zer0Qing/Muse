# Muse 外部引用清理进度

最后更新：2026-08-05

## 批次状态
- [x] P0 rikkahub 重写（8/8 文件）
- [x] P1 kelivo 机制（6/6）
- [ ] P2 openhanako memory（11 重写 + 注释清理）
- [ ] P3 ai/app 注释清理
- [ ] P4 strings + 文档

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

## P0 小结（2026-08-05）
- 处理文件数：8 重写 + 19 注释/目录清理
- 编译状态：通过（`:ai:compileDebugKotlin :material3:compileDebugKotlin :app:compileDebugKotlin`）
- 单测状态：通过（`:ai:testDebugUnitTest :app:testDebugUnitTest`）
- 跳过/阻塞项：无
- 对外接口变更：无

## P1 小结（2026-08-05）
- 处理文件数：6/6
- 编译状态：通过（`:ai:testDebugUnitTest :app:compileDebugKotlin`）
- 单测状态：通过（`:ai:testDebugUnitTest`）
- 跳过/阻塞项：无
- 对外接口变更：无
- 下一批预计：P2 openhanako memory

## 遇到的问题（阻塞项）
- 暂无
