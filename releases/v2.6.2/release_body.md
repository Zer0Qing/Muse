# Muse v2.6.2

> 本版覆盖自 v2.6.1 发布以来的全部改动，以"助手能力补齐"与"连接可靠性修复"为主。

## 新功能

- **助手能管知识库了**：此前助手对知识库只能检索，删文档、建库、删库都做不到（让你"叫 AI 删"时它其实没有这个能力）。现在新增知识库管理工具：列出文档、删除文档、创建知识库、删除知识库（连带清理分块与向量索引）。默认知识库与内部文档受保护不可删；破坏性操作逐条弹出审批，不提供批量删除。
- **助手能管会话了**：可以列出会话、改标题、移到文件夹、归档/取消归档、删除会话。删除不可撤销，同样逐条审批。
- **知识库全文阅读**：新增 `knowledge_read_doc`，助手可以按文档 id 读取全文——一篇 195 页的 PDF 不再只能看到检索出来的零散片段；超长文档自动分段返回（每次 3 万字符，末尾提示继续读的偏移量）。内部文档不允许通过该工具读取。
- **技能与助手自管理**：助手现在可以列出/启用/停用/删除技能，也可以创建助手、修改人设、删除助手。为避免 AI 自我扩权，这套工具只开放安全子集——不允许修改工具/技能/MCP 绑定、模型与供应商，这些仍然只在设置页由你手动配置。
- **中转站请求头自动注入**：新增按中转站域名声明式匹配的必需请求头注入（首批 OpenCode Go → `x-opencode-session`），请求头值按会话派生且稳定，保证路由亲和与缓存命中；后台任务无会话上下文时也能保证请求头存在。你自己的同名自定义头永远优先。
- **OpenAI 型供应商开放自定义请求头/请求体**：此前自定义头输入框只在"自定义"类型供应商下显示，预设的 OpenAI 型供应商（含各中转站）完全没入口；现在也可以填写，任何中转站要求特殊请求头时都能自己加。
- **主动消息「极多」档 + AI 自主调度**：新增"极多"频率档（30 分钟间隔、每日上限 50 条），并支持由 AI 自己决定下次联系你的间隔（10 分钟~24 小时可调），不再是机械等距发送。
- **自带助手也可删除**：助手管理页此前把自带助手的删除入口硬藏起来，现在对所有助手一致开放（自动把默认指针切给其它助手，且至少保留一个）。

## 重要修复

- **Codex 登录后所有请求 403**：修复 ChatGPT 账号 ID 的 JWT 解析路径错误（它在 URL 命名空间下而非顶层）、base64url 缺 padding 解析失败、以及登录回调在解析失败时连 token 都不保存的三处叠加问题。老用户无需重新登录——启动时会自动从已保存的 token 里重新解析并补写请求头（幂等）。
- **Codex 请求再补两处适配**：账号 ID 解析增加 access_token 回退（id_token 缺失的账号也能自愈），并补齐 Responses 协议要求的 `OpenAI-Beta` 请求头。
- **Web 服务启动崩溃**：修复 Ktor 3.x 下 WebServer 启动稳定抛 `Array has more than one element` 的问题（环境自带默认 connector 与配置块重复定义导致）。
- **禁用工具仍被反复调用**：在设置里禁用的工具（如 `app_launch`）此前只在执行层拦下，但定义仍发给模型，导致"模型每轮调用→被拒→下轮继续"的死循环；现在被禁用的工具直接从模型可见的 schema 中剔除。
- **自定义助手收不到主动消息**：修复主动消息生成用自定义助手、写入却选全库最新会话（常常是默认助手房间）的路由错配——现在强制按助手定位会话，收不到的问题根治。
- **「全天允许」夜间仍不发**：评分引擎内置的 22:00-08:00 安静时段与设置页开关是两套互不相干的判断；现在开启"全天允许"后会真正跳过安静时段检查。
- **应用控制与 MCP 工具登记遗漏**：补齐 11 个工具的分类登记、6 个应用控制工具的内置白名单——修复默认助手看不到应用控制类工具、部分 MCP 工具每轮恒发绕过收窄的问题；同时把位置、渲染、主动消息等工具补进对应关键词族。
- **测试连接丢配置**：修复供应商"测试连接"时手搓配置丢掉了 Responses 协议开关与自定义请求头的问题，改为复用统一的配置构建。
- **主动消息评分引擎测试恢复**：恢复被误覆盖的评分引擎历史测试（v1.0.72 的加权平均/阈值用例），并追加本版新用例，防止回归。

## 说明

- 本版以能力补齐与可靠性修复为主，建议升级。
- 打包流程补上了内置运行时（Node/Python）的构建步骤，保证从干净环境打出的包不再缺运行时。

---

# Muse v2.6.2 (English)

> This release covers all changes since v2.6.1, focused on closing assistant capability gaps and connection reliability fixes.

## New

- **The assistant can manage your knowledge base now**: previously it could only search; deleting documents, creating or deleting knowledge bases was impossible. New management tools: list documents, delete a document, create a knowledge base, delete a knowledge base (chunks and vector index are cleaned up as well). The default knowledge base and internal documents are protected; destructive operations each raise their own approval prompt, with no batch deletion.
- **The assistant can manage sessions now**: list sessions, rename, move to a folder, archive/unarchive, and delete sessions. Deletion is irreversible and approved item by item.
- **Full-document reading for the knowledge base**: new `knowledge_read_doc` tool lets the assistant read a whole document by id — a 195-page PDF is no longer just scattered search snippets; long documents are returned in segments (30000 characters each, with the next offset at the end). Internal documents cannot be read through this tool.
- **Skill and assistant self-management**: the assistant can now list/enable/disable/delete skills, and create assistants, edit their personas, and delete them. To prevent self-escalation, only a safe subset is exposed — the assistant cannot modify tool/skill/MCP bindings, models or providers; those remain manual configuration in settings.
- **Automatic relay header injection**: new declarative per-host required-header injection (first rule: OpenCode Go → `x-opencode-session`), with session-derived stable values for routing affinity and cache hits; background tasks without session context still get a stable header. Your own headers with the same name always win.
- **Custom headers/body for OpenAI-type providers**: the custom header input only appeared for "custom" providers before, leaving preset OpenAI-type providers (including relay stations) no way to set required headers; now they can, so any relay's special headers are user-settable.
- **"Extreme" pace for proactive messages + AI-paced scheduling**: a new extreme frequency tier (30-minute interval, 50 messages/day cap), and the assistant can decide its own next-contact interval (10 minutes to 24 hours) instead of a fixed cadence.
- **The built-in assistant can be deleted too**: the delete entry was hard-hidden for the bundled assistant; it is now available for all assistants (the default pointer automatically switches to another assistant, and at least one is always kept).

## Fixes

- **All requests 403 after Codex sign-in**: fixed three stacked issues — the ChatGPT account id JWT claim lives under a URL namespace rather than at the top level, base64url payloads without padding failed to decode, and the login callback did not even save the token when parsing failed. Existing users need no re-login: startup re-parses the stored token and rewrites the header (idempotent).
- **Two more Codex adaptations**: account-id parsing now falls back to the access token (id_token-less accounts self-heal), and the `OpenAI-Beta` header required by the Responses protocol is now sent.
- **Web server startup crash**: fixed the stable `Array has more than one element` crash of WebServer under Ktor 3.x (the environment's default connector plus the configure-block connector made the connector list ambiguous).
- **Disabled tools kept being called**: tools disabled in settings (e.g. `app_launch`) were only blocked at execution time while their definitions were still sent to the model, causing a call → reject → call-again loop; disabled tools are now removed from the model's schema directly.
- **Custom assistants received no proactive messages**: proactive messages were generated with your custom assistant but written to the newest session in the entire database (often the default assistant's room); sessions are now resolved strictly per assistant.
- **"All day" still blocked at night**: the scoring engine's built-in 22:00-08:00 quiet hours were independent from the settings switch; enabling "all day" now truly skips the quiet-hours check.
- **Missing tool registrations**: 11 tools lacked category registration and 6 app-control tools were missing from the built-in whitelist — the default assistant could not see app-control tools, and some MCP tools bypassed narrowing by being sent every turn; location/render/proactive tools were also added to their keyword families.
- **Test-connection losing configuration**: the provider "test connection" path hand-built a config that dropped the Responses protocol flag and custom headers; it now reuses the shared config builder.
- **Proactive scoring engine tests restored**: the accidentally overwritten scoring engine tests (v1.0.72 weighted-average/threshold cases) were restored, with new cases for this release added to prevent regressions.

## Notes

- This release is primarily capability completion and reliability fixes; upgrading is recommended.
- The build pipeline now packs the embedded runtimes (Node/Python) explicitly, so builds from a clean checkout no longer ship without them.
