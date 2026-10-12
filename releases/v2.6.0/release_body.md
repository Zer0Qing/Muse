Muse v2.6.0 功能版（候选，真机验收中，未正式发布）。

## 新功能

- **Tasker 外部自动化入口**：默认关闭；设置页可显式启用外部广播触发一轮文本补全，并提示可能消耗模型额度。不会授予本地工具权限。
- **GPT 账号登录（Codex OAuth）**：支持用 ChatGPT 账号一键登录（浏览器授权 + PKCE），自动注入 ChatGPT-Account-ID 请求头，直连 `chatgpt.com/backend-api/codex` 使用订阅额度，无需 API Key。
- **诊断增强**：设置页「导出诊断信息」覆盖生成链路环形缓冲；工具调用首片异常时记录原始片段，便于排查上游格式异常。

## 重要修复

- **A2A 远程对话隔离**：远程请求不再切换前台聊天或调用本地设备工具；context/task 隔离，多轮文本对话仍可继续，取消只影响对应请求。
- **Agent 助手切换不生效**：修复 Agent 房间恢复时错误回退到其他助手的旧会话，切换助手后房间归属现在正确。
- **默认助手保护**：默认助手不再可被直接删除；删除其他助手时指针自动切换，且重启后不会"复活"已删除的默认助手。
- **模型能力标签**：自建/中转站供应商不再被目录误标"不支持图像"，显示为未知而非错误结论。
- **供应商测试按钮误报**：两级探测（模型列表失败降级健康检查），错误信息不再截断。
- **报错信息可展开、可复制**：长错误支持展开阅读与长按复制。
- **Termux 授权跳转**：授权失败多级回退并给出指引提示。
- **Responses API 空消息**：修复部分上游返回空 content 时的 400 死循环。
- **消息气泡**：有背景图时长文本保留气泡底色；深色壁纸下正文自动反色提升可读性。

## 体验改进

- 消息快捷操作全部可配置（复制/思考/翻译/分享/重新生成/删除），全关时不再留空行。
- 思考复制默认关闭、删除消息带二次确认，减少误触。
- ChatViewModel 拆分重构（工具循环/压缩编排/审批/恢复独立模块），降低后续维护成本。

---

# Muse v2.6.0 (English)

Muse v2.6.0 feature release (candidate; under device testing, not yet published).

## New Features

- **Tasker external automation**: off by default; Settings can explicitly enable external broadcasts for a single text-completion request, with a model-quota warning. It does not grant local tool access.
- **GPT account login (Codex OAuth)**: one-tap sign-in with a ChatGPT account (browser authorization + PKCE), automatically injecting the ChatGPT-Account-ID header and connecting to `chatgpt.com/backend-api/codex` to use subscription quota — no API key required.
- **Diagnostics enhancements**: the in-app "Export diagnostics" now covers a generation-trace ring buffer; raw tool-call fragments are logged when the first chunk is lost, easing upstream anomaly triage.

## Important Fixes

- **Isolated A2A remote conversations**: requests no longer switch foreground chats or invoke local device tools; task/context sessions are isolated, multi-turn text conversations remain supported, and cancellation affects only the caller's request.
- **Agent assistant switching**: fixed Agent rooms restoring from another assistant's stale session; room ownership now follows the selected assistant.
- **Default assistant protection**: the default assistant can no longer be deleted directly; the pointer switches automatically and deleted defaults no longer "revive" after restart.
- **Model capability labels**: custom/proxied providers are no longer mislabeled "image unsupported"; they show unknown instead of a wrong conclusion.
- **Provider test button false alarms**: two-stage probing (falls back to health check when model listing fails) and untruncated error messages.
- **Expandable, copyable errors**: long errors can be expanded and long-pressed to copy.
- **Termux authorization jump**: multi-level fallback with guidance toasts when authorization fails.
- **Responses API empty messages**: fixed a 400 retry loop when some upstreams return empty content.
- **Message bubbles**: long text keeps a bubble background over wallpaper images; body text auto-inverts on dark backgrounds for readability.

## Experience Improvements

- All message quick actions are configurable (copy/reasoning/translate/share/regenerate/delete); hiding them all no longer leaves an empty row.
- Reasoning copy is off by default and message deletion requires confirmation to reduce accidents.
- ChatViewModel refactor split (tool loop / compression / approval / resume coordinators) to lower maintenance cost.
