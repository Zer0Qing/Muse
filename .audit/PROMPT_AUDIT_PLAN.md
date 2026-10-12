# 提示词体系审查与修复计划

审查范围：Muse 全项目提示词资产（assets 模板 / SystemPromptAssembler / memory 模块 / 各子系统内联）。
产出：分级修复清单 + 逐项落地记录。

## 背景：问题的共同根源

提示词是一套**没有编译器和测试守护的"影子系统"**：
- 中英双份手写，靠人自觉保持一致 → 已漂移
- 多语言只覆盖 zh/en，通用模板实质=中文 → ja/ko/es/pt/ru 用户拿中文
- 同一件事多处各写一套（MOOD、压缩、人设） → 定义冲突
- 硬编码中文检查 `check_hardcoded_cjk.py` 只扫 `ui/` → 其余目录是盲区

## 分级清单

### P0 — 影响真实用户体验（本批修复）

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| P0-1 | 非 zh/en 语言回落中文 | `PromptTemplateLoader.loadTemplate` / `AssistantRepository.loadDefaultPrompt` | 非中文 locale 回落 `_en` 而非通用(=中文) |
| P0-2 | 群聊 MOOD 与单聊是两套定义（Vibe 语义相反） | `GroupChatScheduler.GROUP_CHAT_MOOD_SECTION` | 复用单聊 `mood_format` 模板 |
| P0-3 | 子代理强制中文输出 | `SubagentRunner.buildSystemPrompt` | 跟随 locale |
| P0-4 | 内置场景模板 content 硬编码中文 | `PromptTemplate.getBuiltInPromptTemplates` | 走 stringResource |
| P0-5 | 缺 `cover_generation_en.prompt` | assets | 补英文版 |

### P1 — 一致性缺陷（本批修复）

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| P1-1 | 未来日期规则中英缺一 + 示例 time 不一致 | `FactExtractionPrompt` | 英文补规则、修示例 |
| P1-2 | 花名册用途限制/记忆排除范围中英缺一 | `RollingSummaryPrompt` | 英文补齐 |
| P1-3 | 共享契约只有中文 | `MemoryPromptContract` | 加英文分支 |
| P1-4 | decision_tree 英文缺"工具 schema 可用" | `decision_tree_en.prompt` | 补齐 |
| P1-5 | 默认人设 emoji 规则冲突 | `PresetCharacters.MUSE` vs `default_persona` | **判定不成立**：PresetCharacters 是可选角色库（无外部引用），与默认助手人格是不同对象；角色间 emoji 差异属有意设计，不改 |
| P1-6 | 8 个内置预设角色人设无本地化（硬编码中文） | `PresetCharacters` | **下批**：预设角色是用户主动挑选的可选库，非默认路径；翻译 8 套人设成本高，优先级低于默认助手 |

### P2 — 防漂移工程（本批修复）

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| P2-1 | 硬编码中文检查只扫 ui/ | `check_hardcoded_cjk.py` | 扩展扫描目录（含 baseline 更新） |
| P2-2 | 无中英提示词一致性测试 | app/memory test | 加"模板成对存在 + 条目数一致"测试 |

### P3 — 低危（记录，不本批改）

- legacy 模板（6 个）：仅用于旧数据迁移比对，正常归档
- legacy persona "表情包/emojis" 术语漂移：影响小
- 压缩提示词两套（`ConversationCompressor` vs `ContextCompressTransformer`）：语义相近，暂不合并
- 各子系统内联中文提示词（工具清单/Workspace/多 Agent/群聊/翻译/日记等）国际化：工作量大，留下批

## 执行顺序

1. P0-1 回落策略（核心，高杠杆）
2. P0-5 补 cover_generation_en
3. P0-2 群聊 MOOD 统一
4. P0-3 子代理 locale
5. P0-4 场景模板国际化
6. P1 全部
7. P2 防漂移
8. 门禁 + 打包 2.6.0

## 附：genui 卡片渲染器（v2.6.0 本轮修复）

### 问题

- `show_card` 工具与 `generative_ui` 提示词早已存在，但**卡片的 HTML 从未有渲染入口**——工具只把 `data` 写进 `CardDataStore`、返回文本摘要，`code` 无处渲染。
- 用户实际看到的是工具卡展开后的**原始参数键值对**（title/code/data）。

### 修法

- `ToolCallCard` 识别 `show_card`：参数含 `code` 时，展开区直接渲染 `RichContentCard`（复用 WebView + 主题令牌 + 卡桥 + 安全清洗），不再显示参数区/结果文本。
- `RichContentCard` 新增 `inlineCardData`：把 `show_card` 的 `data` 内联给卡片脚本的 `window.muse.getData()`，避开 cardId 跨消息查找的断裂。
- `show_card` 视为内容卡：不受「显示工具调用详情」开关控制。
- 新增 `parseShowCardArgs`（含单测）。
- 修 `QuickAction.EDIT`：只对用户消息可见（旧版无条件 true，会挂在任何助手消息下）。

### 改动文件

- `app/.../ui/MessageBubbleExtras.kt` — show_card 解析 + 内联渲染分支
- `app/.../ui/markdown/RichContentCard.kt` — inlineCardData 参数
- `app/.../ui/MessageBubble.kt` — 透传 onCardAction + 内容卡开关豁免 + EDIT 可见性
- 测试：`app/src/test/.../ui/ShowCardArgsTest.kt`

- [x] P0-1 ~ P0-5（多语言回落 / 群聊 MOOD 统一 / 子代理 locale / 场景模板国际化 / cover_en 补齐）
- [x] P1-1 ~ P1-4（中英漂移修复：FactExtraction / RollingSummary / MemoryPromptContract / decision_tree）
- [x] P2 防漂移测试（模板配对 + 非中文 locale 不拿中文）+ memory 契约双语测试
- [ ] 门禁 + 打包（ktlint/detekt/台账/全量单测已过，待打包）

### 改动文件

- `app/src/main/java/io/zer0/muse/transformer/PromptTemplateLoader.kt` — 非中文回落英文
- `app/src/main/java/io/zer0/muse/data/assistant/AssistantRepository.kt` — loadDefaultPrompt 非中文回落英文
- `app/src/main/java/io/zer0/muse/schedule/GroupChatScheduler.kt` — 群聊 MOOD 复用单聊模板
- `app/src/main/java/io/zer0/muse/tools/SubagentRunner.kt` — 子代理提示词双语跟随 locale
- `app/src/main/java/io/zer0/muse/data/prompttemplate/PromptTemplate.kt` — 内置模板正文走 stringResource
- `app/src/main/java/io/zer0/muse/data/MemoryLlmClientImpl.kt` — 记忆契约按 locale 追加
- `app/src/main/res/values/strings_data.xml` + 6 个 locale — 13 条模板正文 key
- `app/src/main/assets/prompt_templates/cover_generation_en.prompt` — 新增
- `memory/.../FactExtractionPrompt.kt` — 英文补未来日期规则 + 修示例 time
- `memory/.../RollingSummaryPrompt.kt` — 英文补花名册限制 + 排除范围
- `memory/.../MemoryPromptContract.kt` — 契约双语
- `app/src/main/assets/prompt_templates/decision_tree_en.prompt` — 补「工具 schema 可用」
- 测试：`PromptTemplateLoaderTest`（新增 6 个）+ `MemoryPromptContractTest`（新增 2 个）

### 未做（记录，下批）

- 各子系统内联中文提示词的完整国际化（工具清单/Workspace/多 Agent/群聊/翻译/日记/朋友圈/视觉/RAG 等）
- 8 个内置预设角色人设的本地化
- 压缩提示词两套合并（`ConversationCompressor` vs `ContextCompressTransformer`）
- `check_hardcoded_cjk.py` 扩到非 ui 目录（改用模板配对测试做守卫，理由：全目录扫描会炸出大量合法的内部提示词噪声）
- ja/ko/es/pt/ru 五语言提示词精品翻译（当前回落英文）
