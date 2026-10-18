# Muse v2.6.2

> 本版覆盖自 v2.6.1 发布以来的全部改动，以"助手能力补齐"、"记忆可靠性"与"个性化"为主。

## 新功能

- **每个对话单独设背景**：聊天背景支持三层——会话级、助手级、全局默认。优先级依次回落，给不同用途的助手各设一张，互不干扰；单个对话还能再覆盖。图片统一压缩落盘，不会撑大设置文件。
- **背景跟随明暗自动切换**：新增「深色模式背景」。开了明暗自动切换后，浅色用一张、深色自动换成另一张；不设则沿用主背景。
- **助手人格可编辑**：助手详情 → 提示词页新增「人格定义」，独立于完整自定义提示词。轻量定制（你是谁、怎么说话）用前者，完全接管用后者。
- **自定义思考过程**：同一页新增「自定义思考过程」，用来强约束思考链构成，例如"先复述问题 → 列 2-3 个假设 → 逐个排除 → 再给结论"。留空则按默认方式思考。
- **助手能翻看其他会话**：新增 `session_read`，助手可按会话 id 读取该会话最近的消息（默认 20 条，单条与总量都有上限），配合会话列表就能回顾你以前聊过什么。
- **通知 → 记忆**：通知可以自动梳理成记忆（默认关闭，需在通知页手动开启），梳理时会去重整合；开启后助手能在主动消息里提起通知里的事。
- **记忆星座**：新增知识图谱视图，支持自动补边（同实体、同会话、同分类、语义近邻）与手动连线；置顶、确认关系都能在图上直接操作。
- **助手能管知识库了**：列出文档、删除文档、创建知识库、删除知识库（连带清理分块与向量索引）。默认知识库与内部文档受保护；破坏性操作逐条审批。
- **助手能管会话了**：列出会话、改标题、移到文件夹、归档/取消归档、删除会话。删除不可撤销，逐条审批。
- **知识库全文阅读**：新增 `knowledge_read_doc`，按文档 id 读全文，超长自动分段（每次 3 万字符，末尾给出继续读取的偏移量）。
- **技能与助手自管理**：助手可列出/启用/停用/删除技能，也可创建助手、改人设、删助手。只开放安全子集——工具/技能/MCP 绑定、模型与供应商仍只在设置页手动配置。
- **中转站请求头自动注入**：按域名声明式匹配必需请求头（首批 OpenCode Go → `x-opencode-session`），值按会话派生且稳定；你自己的同名自定义头永远优先。
- **OpenAI 型供应商开放自定义请求头/请求体**：预设的 OpenAI 型供应商（含各中转站）也能填写，不再只有"自定义"类型有入口。
- **主动消息「极多」档 + AI 自主调度**：新增极多档（30 分钟间隔、每日上限 50 条），并支持由 AI 自己决定下次联系间隔（10 分钟~24 小时）。
- **自带助手也可删除**：对所有助手一致开放（自动切换默认指针，且至少保留一个）。

## 重要修复

- **明暗主题不随系统即时切换**：此前只有在对应主题下启动应用才生效——因为配置变更不重建 Activity 时，读取的仍是缓存的旧系统配置。现在改为显式监听系统深浅色变化，运行中切换立刻生效。
- **收藏夹点不开原文**：卡片点击此前被"分组选择"占用，且预览只截 280 字。现在点击直接打开全屏可滚动原文，分组挪到卡片内按钮。
- **工具成败误判**：此前按中文前缀猜成败，读到以"错误/失败"开头的正常文档会被判失败并触发无谓重试。现在结构化失败信号优先，长正文不再误判。
- **高危工具重试绕过审批**：失败步骤点"重试"会直接重发，绕过权限与审批——发短信、删数据这类操作可能重复执行。现在高危工具不再自动重试，提示重新发起。
- **工具参数解析两套并存**：hook 与参数化审批看到的参数和执行时不一致，畸形 JSON 时甚至会让 `file://` 这类拦截失效。现在统一同源解析并保留类型。
- **记忆数据丢失风险**：修复 LLM 合并"先删后写"导致的不可逆丢失（改为单事务原子合并）、摘要并发覆盖、深度处理重复抽取、每日进度只存单行导致切换空间后当天不编译等问题。
- **记忆跨空间串库**：编译读写统一 target，修复子助手/其他空间的编译产物写错槽位、反思路由到默认库、星座置顶改错库等一串问题。
- **Codex 登录后所有请求 403**：修复账号 ID 的 JWT 解析路径错误（在 URL 命名空间下而非顶层）、base64url 缺 padding、以及登录回调解析失败时连 token 都不保存的三处叠加问题。老用户无需重新登录，启动时会自动自愈。
- **Codex 请求再补两处适配**：账号 ID 解析增加 access_token 回退，并补齐 Responses 协议要求的 `OpenAI-Beta` 头。
- **Web 服务启动崩溃**：修复 Ktor 3.x 下稳定抛 `Array has more than one element` 的问题。
- **禁用工具仍被反复调用**：被禁用的工具直接从模型可见的 schema 中剔除，不再"调用→被拒→继续调用"死循环。
- **自定义助手收不到主动消息**：强制按助手定位会话，修复生成与写入路由错配。
- **「全天允许」夜间仍不发**：开启后真正跳过内置安静时段检查。
- **应用控制与 MCP 工具登记遗漏**：补齐分类登记与内置白名单，修复默认助手看不到应用控制工具、部分 MCP 工具每轮恒发的问题。
- **测试连接丢配置**：复用统一配置构建，不再丢 Responses 协议开关与自定义请求头。

## 说明

- 本版以能力补齐、可靠性修复与个性化为主，建议升级。
- 本版包含数据库升级（v110 → v111，新增助手人格与思考链字段），首次启动会自动迁移。

---

# Muse v2.6.2 (English)

> This release covers all changes since v2.6.1, focused on closing assistant capability gaps, memory reliability and personalization.

## New

- **Per-conversation chat backgrounds**: backgrounds now have three tiers — per conversation, per assistant, and a global default, resolved in that order. Give each assistant its own background and conversations can still override it. Images are compressed to disk so settings stay small.
- **Backgrounds follow light/dark automatically**: a new "dark mode background" is used when auto light/dark switching is on. Leave it unset to reuse the main background.
- **Editable assistant persona**: the assistant detail → prompt page gains a "persona definition" field, separate from the full custom prompt. Use the former for light customization, the latter to take over entirely.
- **Custom thinking process**: the same page gains a field that constrains how the assistant structures its reasoning, e.g. restate the question -> list 2-3 hypotheses -> rule them out -> conclude. Leave empty for default reasoning.
- **The assistant can read other conversations**: new `session_read` reads the most recent messages of a session by id (20 by default, with per-message and total caps), so it can recall what you discussed elsewhere.
- **Notifications to memory**: notifications can be distilled into memories automatically (off by default, enabled on the notification page), deduplicated and consolidated; once enabled the assistant can bring them up in proactive messages.
- **Memory constellation**: a new knowledge-graph view with automatic edge building (same entity, same session, same category, semantic neighbors) and manual linking; pinning and confirming relations work directly on the graph.
- **The assistant can manage your knowledge base**: list documents, delete a document, create or delete a knowledge base (chunks and vector index cleaned up). The default knowledge base and internal documents are protected; destructive operations are approved one by one.
- **The assistant can manage sessions**: list, rename, move to a folder, archive/unarchive and delete. Deletion is irreversible and approved item by item.
- **Full-document reading**: new `knowledge_read_doc` reads a whole document by id, returning long documents in segments (30000 characters each with the next offset).
- **Skill and assistant self-management**: list/enable/disable/delete skills, create assistants, edit personas, delete assistants. Only a safe subset is exposed — tool/skill/MCP bindings, models and providers remain manual configuration.
- **Automatic relay header injection**: declarative per-host required headers (first rule: OpenCode Go -> `x-opencode-session`), with session-derived stable values. Your own headers with the same name always win.
- **Custom headers/body for OpenAI-type providers**: preset OpenAI-type providers (including relays) can now set them too.
- **"Extreme" proactive pace + AI-paced scheduling**: a new extreme tier (30-minute interval, 50/day cap), and the assistant can pick its own next-contact interval (10 minutes to 24 hours).
- **The built-in assistant can be deleted too**: available for all assistants (the default pointer switches automatically, at least one is kept).

## Fixes

- **Theme did not follow the system immediately**: dark mode only applied if the app started under that theme, because configuration changes without an Activity rebuild left a stale cached configuration. It now listens to system light/dark changes explicitly and applies instantly.
- **Favorites could not be opened**: tapping a card was taken over by the group picker and the preview was capped at 280 characters. Tapping now opens the full scrollable text, with grouping moved to a button on the card.
- **Tool success misjudged**: success was guessed from Chinese prefixes, so reading a document that starts with "error"/"failure" was marked failed and triggered pointless retries. Structured failure signals now take priority.
- **High-risk retries bypassed approval**: retrying a failed step re-ran it directly, bypassing permissions and approval — messages could be sent or data deleted twice. High-risk tools are no longer auto-retried.
- **Two parameter parsers**: hooks and parameterized approval saw different arguments than execution, and malformed JSON could even disable `file://` blocking. Parsing is now unified with types preserved.
- **Memory data-loss risks**: fixed irreversible loss in LLM consolidation (now a single atomic transaction), concurrent summary overwrites, duplicate deep extraction, and daily progress stored as a single row that skipped compilation after switching spaces.
- **Memory leaking across spaces**: compilation read/write now uses a single target, fixing wrong-slot writes, reflection running against the default store, and constellation pinning hitting the wrong database.
- **All requests 403 after Codex sign-in**: fixed the account-id JWT claim path (under a URL namespace, not top level), base64url without padding, and the login callback not saving the token when parsing failed. Existing users self-heal on startup without re-login.
- **Two more Codex adaptations**: access-token fallback for account id, plus the `OpenAI-Beta` header required by the Responses protocol.
- **Web server startup crash**: fixed the `Array has more than one element` crash under Ktor 3.x.
- **Disabled tools kept being called**: disabled tools are removed from the model's schema, ending the call-reject-call loop.
- **Custom assistants received no proactive messages**: sessions are now resolved strictly per assistant.
- **"All day" still blocked at night**: enabling it now truly skips the built-in quiet hours.
- **Missing tool registrations**: category registration and the built-in whitelist were completed.
- **Test-connection losing configuration**: now reuses the shared config builder.

## Notes

- This release is primarily capability completion, reliability fixes and personalization; upgrading is recommended.
- It includes a database upgrade (v110 -> v111, new persona and thinking-chain fields), migrated automatically on first launch.
