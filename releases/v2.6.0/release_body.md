Muse v2.6.0 功能版。

> 本版更新日志覆盖自 v2.5.8 发布以来的全部改动（含未单独发布的 v2.5.9 内容）。

## 新功能

- **GPT 账号登录（Codex OAuth）**：支持用 ChatGPT 账号一键登录（浏览器授权 + PKCE），自动注入 ChatGPT-Account-ID 请求头，直连 `chatgpt.com/backend-api/codex` 使用订阅额度，无需 API Key；并支持一键直达授权页。统一修复 xAI / GitHub 等一键 OAuth 登录（补全公开 client_id、Device Flow 验证码弹窗）。
- **Codex 模型清单接云端目录**：Codex 可用模型以云端模型目录为准（不再在应用内预置），跟随官方退役/新增自动更新。
- **生成式卡片（show_card）**：助手可在对话里直接生成可交互卡片（图表、仪表盘、流程/对比图、可玩组件），本版补全了卡片渲染器。
- **Tasker 外部自动化入口**：默认关闭；设置页可显式启用外部广播触发一轮文本补全，并提示可能消耗模型额度。不会授予本地工具权限。
- **消息快捷操作可配置**：Via 式定制菜单，全量动作池 + 有序启用列表（复制/思考/翻译/分享/重新生成/删除等）。
- **备份可选包含供应商密钥**：备份时可选是否包含供应商 API 密钥。
- **平台自检工具**：新增 `platform_selfcheck`，助手可自查运行环境与能力就绪状态。
- **诊断增强**：设置页「导出诊断信息」覆盖生成链路环形缓冲；工具调用首片异常时记录原始片段，便于排查上游格式异常。

## 重要修复

- **A2A 远程对话隔离**：远程请求不再切换前台聊天或调用本地设备工具；context/task 隔离，多轮文本对话仍可继续，取消只影响对应请求。
- **A2A 安全加固**：全局“批准全部”位不再作用于非焦点会话，堵住远程静默执行高风险设备工具的路径。
- **Tasker 广播安全防线**：入口默认关闭 + 长度上限 + 限频，降低外部广播被滥用的成本。
- **Agent 助手切换不生效**：修复 Agent 房间恢复时错误回退到其他助手的旧会话，切换助手后房间归属现在正确。
- **默认助手保护**：默认助手不再可被直接删除；删除其他助手时指针自动切换，且重启后不会“复活”已删除的默认助手。
- **模型能力标签**：自建/中转站供应商不再被目录误标“不支持图像”，显示为未知而非错误结论。
- **供应商测试按钮误报**：两级探测（模型列表失败降级健康检查），错误信息不再截断。
- **报错信息可展开、可复制**：长错误支持展开阅读与长按复制。
- **Termux 授权跳转**：授权失败多级回退并给出指引提示。
- **Responses API 空消息**：修复部分上游返回空 content 时的 400 死循环。
- **消息气泡**：有背景图时长文本保留气泡底色；深色壁纸下正文自动反色提升可读性。
- **知识库检索结果**：不再以正文气泡形式出现在聊天前端。
- **通知监听页**：修复筛选与按钮截断问题。
- **编辑按钮越权**：编辑按钮不再出现在助手消息（含卡片）下方，只应出现在用户自己发的消息上。

### 深度审查修复（本轮新增）

- **数据一致性 / 并发**：断点恢复与前台刷新写入消息改走原子 CAS，压缩完成后不再把已切走会话的水位线写进全局（修串会话）；切换助手房间引用计数先增后减，避免误释放在用会话；导入备份补入库后真实行数核对。
- **RAG 可靠性**：内置功能文档索引比对 embedding 模型指纹；embedding 指纹改用稳定维度口径，消除因猜测维度导致的 HNSW 索引冷启动重建。
- **渠道可靠性**：Telegram 游标损坏不再终结接收循环；回发令牌解密失败转终态（不再无限重试）；飞书 webhook 入站解析 content JSON（与长连接路径一致）；自动回复先发送成功再记历史（修上下文漂移）；Tasker 结果通知改读用户全局策略；渠道存储损坏隔离。
- **备份可靠性**：知识库文件夹接入 NDJSON 恢复与快照，修复“恢复即丢文件夹”。
- **记忆可靠性**：记忆自动保存“未解析出分析”不再抛异常。
- **工具与 UI**：修复工具嵌套对象参数（如 render_data 的 data）被当作转义字符串导致的解析失败；审批暂停态离开时正确复位；全屏编辑页补 imePadding。
- **CI / 工程健康**：源码行尾统一 LF（消除基线行号漂移噪音）；detekt 台账同步实测；NOTICE 补齐漏列依赖；统一 22 个 Robolectric 测试的 SDK 配置，根治全量跑随机失败。
- **提示词体系**：非中文语言（日/韩/西/葡/俄）不再回落到中文提示词，改为回落英文；群聊 MOOD 复用单聊模板（旧版两处定义已漂移）；子代理提示词跟随应用语言（旧版硬编码中文输出）；内置场景模板正文接入本地化资源；修复记忆抽取/滚动摘要的中英规则漂移。

## 体验改进

- **生成式卡片可直接渲染**：助手用 show_card 生成的图表/仪表盘/可交互界面现在能在对话里真正渲染（此前只显示工具参数原文）；卡片内脚本也能通过 data 绑定读取数据。
- 消息快捷操作全部可配置，全关时不再留空行；思考复制默认关闭、删除消息带二次确认，减少误触。
- ChatViewModel 拆分重构（工具循环/压缩编排/审批/恢复独立模块），降低后续维护成本。

---

# Muse v2.6.0 (English)

Muse v2.6.0 feature release.

> This changelog covers all changes since the v2.5.8 release (including the never-published v2.5.9 contents).

## New Features

- **GPT account login (Codex OAuth)**: one-tap sign-in with a ChatGPT account (browser authorization + PKCE), automatically injecting the ChatGPT-Account-ID header and connecting to `chatgpt.com/backend-api/codex` to use subscription quota — no API key required; with a direct jump to the authorization page. Unified fixes for one-tap OAuth on xAI / GitHub (filled in public client_id, Device Flow user-code dialog).
- **Codex model list from the cloud catalog**: available Codex models now come from the cloud model catalog (no longer preset in the app) and follow official retirements/additions automatically.
- **Generative cards (show_card)**: the assistant can render interactive cards directly in the conversation (charts, dashboards, flow/comparison diagrams, playable widgets); this release completes the card renderer.
- **Tasker external automation**: off by default; Settings can explicitly enable external broadcasts for a single text-completion request, with a model-quota warning. It does not grant local tool access.
- **Configurable message quick actions**: a Via-style customization menu with a full action pool and an ordered enabled list (copy/reasoning/translate/share/regenerate/delete, etc.).
- **Backup can include provider keys**: choose whether to include provider API keys in a backup.
- **Platform self-check tool**: a new `platform_selfcheck` lets the assistant verify its runtime and capability readiness.
- **Diagnostics enhancements**: the in-app "Export diagnostics" now covers a generation-trace ring buffer; raw tool-call fragments are logged when the first chunk is lost, easing upstream anomaly triage.

## Important Fixes

- **Isolated A2A remote conversations**: requests no longer switch foreground chats or invoke local device tools; task/context sessions are isolated, multi-turn text conversations remain supported, and cancellation affects only the caller's request.
- **A2A hardening**: the global "approve all" flag no longer applies to non-focused sessions, closing a path to remotely execute high-risk device tools silently.
- **Tasker broadcast defenses**: off by default plus length caps and rate limiting, raising the cost of abuse.
- **Agent assistant switching**: fixed Agent rooms restoring from another assistant's stale session; room ownership now follows the selected assistant.
- **Default assistant protection**: the default assistant can no longer be deleted directly; the pointer switches automatically and deleted defaults no longer "revive" after restart.
- **Model capability labels**: custom/proxied providers are no longer mislabeled "image unsupported"; they show unknown instead of a wrong conclusion.
- **Provider test button false alarms**: two-stage probing (falls back to health check when model listing fails) and untruncated error messages.
- **Expandable, copyable errors**: long errors can be expanded and long-pressed to copy.
- **Termux authorization jump**: multi-level fallback with guidance toasts when authorization fails.
- **Responses API empty messages**: fixed a 400 retry loop when some upstreams return empty content.
- **Message bubbles**: long text keeps a bubble background over wallpaper images; body text auto-inverts on dark backgrounds for readability.
- **Knowledge search results**: no longer appear in the chat as body-text bubbles.
- **Notification listener page**: fixed filtering and button truncation issues.
- **Edit-button leak**: the edit button no longer appears under assistant messages (including cards) — it belongs only on the user's own messages.

### Deep-audit fixes (this cycle)

- **Data consistency / concurrency**: resume and foreground-refresh message writes now use atomic CAS; a finished compression no longer writes a switched-away session's watermark into global state (cross-session leak); assistant-room switching increments before decrementing refcounts to avoid releasing a session still in use; backup import reconciles real inserted row counts.
- **RAG reliability**: built-in doc indexing now compares the embedding model fingerprint; the embedding fingerprint uses a stable dimension token, eliminating cold-start HNSW rebuilds caused by a guessed dimension.
- **Channel reliability**: a corrupt Telegram offset no longer kills the receive loop; reply-context token decrypt failure becomes a terminal state (no infinite retry); Feishu webhook inbound parses the content JSON (matching the long-connection path); auto-replies record history only after a successful send (fixes context drift); Tasker result notifications honor the user's global policy; corrupt channel storage is quarantined.
- **Backup reliability**: knowledge folders are restored from NDJSON backups and snapshots, fixing "restore loses folders".
- **Memory reliability**: memory auto-save no longer throws when no analysis is parsed.
- **Tools and UI**: fixed nested-object tool arguments (e.g. render_data's data) being passed as escaped strings and failing to parse; approval pause now resets when leaving; the full-screen editor gained imePadding.
- **CI / engineering health**: source line endings normalized to LF (removes baseline line-drift noise); detekt debt cap resynced with measurements; NOTICE lists previously missing dependencies; 22 Robolectric tests pinned to an explicit SDK, eliminating random full-run failures.
- **Prompt system**: non-Chinese locales (Japanese/Korean/Spanish/Portuguese/Russian) no longer fall back to Chinese prompts, falling back to English instead; group-chat MOOD now reuses the single-chat template (the two definitions had drifted); sub-agent prompts follow the app language (previously hardcoded Chinese output); built-in scene template bodies moved to localized resources; fixed zh/en rule drift in memory extraction and rolling summary.

## Experience Improvements

- **Generative cards render for real**: cards produced by the assistant via show_card (charts, dashboards, interactive UIs) now actually render in the conversation (previously only the raw tool arguments were shown); card scripts can also read their bound data.
- All message quick actions are configurable; hiding them all no longer leaves an empty row. Reasoning copy is off by default and message deletion requires confirmation to reduce accidents.
- ChatViewModel refactor split (tool loop / compression / approval / resume coordinators) to lower maintenance cost.


