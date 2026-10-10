Muse v2.5.8 正式版。

本版跨度较大（自 v2.5.6 起累积），主线有四条：**接入外部协议与自动化**、**放开插件沙盒与扩展点**、**把「出问题能定位」做成产品能力**、以及**生成链路的架构清理**。

## 外部协议接入

- **A2A 1.0 协议**：新增 Agent Card 发现端点（`/.well-known/agent-card.json`）与 JSON-RPC 端点（`/a2a`），支持 SendMessage / GetTask / CancelTask。A2A 调用与真机聊天复用同一条生成链路（模型解析、工具、审批、记忆），不存在第二套生成逻辑。
- **Tasker 联动**：新增广播入口 `io.zer0.muse.action.TASKER_TRIGGER`（extra `prompt`），可由 Tasker / MacroDroid 等自动化平台触发一轮对话，结果落库并通知回传。后台单轮执行，不授权工具。

## 插件能力放开

- **插件 Hook 体系**：插件可在 manifest 声明 `hooks`，并在入口脚本导出 `onPromptFinalize(event)`，于提示词定稿前改写历史。失败或返回不合法时保留原历史（fail-open），不阻断生成。
- **插件沙盒放开**：放开 fetch / XHR / WebSocket 网络访问，桥接动作全放行，能力名只校验格式。保留两类底线：页面导航仍禁止，`file://` 与 `content://` 读取仍隔离。
- **签名兼容**：manifest 参与签名的字段改为显式白名单，新增字段默认不参与签名，存量已签名的插件包不受影响。

## 排障能力

- **诊断日志导出**：新增生成链路轨迹记录仪，记录每轮的终态、耗时、首 token 延迟、字符统计、流收尾分类、finish_reason 与压缩触发标记。入口在「设置 → 关于 → 导出诊断信息」。
  红线：不记录消息正文与 API key，会话标识哈希脱敏。
- **子代理超时**：默认超时由 120s 提高到 240s，新增 `timeout_ms` 参数（30s~900s）；超时错误附带进展信息（已执行轮次、工具调用次数、是否有部分总结），便于续接而非整体重来。

## 生成链路架构清理

- 工具调用循环体（含流式单轮处理）、上下文压缩编排、工具审批、待恢复工具调用四块逻辑从主视图模型中整体提取为独立组件，主文件由 7483 行降至 5141 行。**迁移为纯抽取，行为不变**，并补齐了压缩计数与错误分类的单元测试。
- 修复压缩阈值多处判定导致的行为分叉，收敛为单一真源；system prompt 增加总量闸门，长会话不再因提示词膨胀挤爆预算。

## 继承自 v2.5.7 的改动

- 修复上下文超限误报：模型未提供窗口大小时，兜底窗口由 32K 上调至 128K。
- 硬拦截阈值调整，为客户端估算与服务端计数的误差留出余量。

## 界面与其它

- 侧边导航条重写为按内容高度线性映射，滑块位置对应已滚动内容量；修复长消息导致的拖动跳位，两端加入吸附。
- 新增生成式界面（渐进渲染 + 移动端适配 + 安全加固）；新增浏览器截图能力，让模型能看到页面渲染结果。
- 修复多模态开关保存后失效、跳到底部按钮卡位、玻璃强度档位等问题。
- 数据库版本 110，自 v2.5.4 起未变，直接升级不丢失数据。

## 安装

- 通用包：Muse_v2.5.8_universal.apk（任意设备）
- 64 位：Muse_v2.5.8_arm64-v8a.apk（绝大多数现代手机）
- 32 位：Muse_v2.5.8_armeabi-v7a.apk（较老设备）

---

# Muse v2.5.8 (English)

Muse v2.5.8 stable release.

This release spans a wide range of work accumulated since v2.5.6, along four main lines: **external protocol and automation integration**, **opening up the plugin sandbox and extension points**, **turning "we can locate the problem when it happens" into a product capability**, and **architectural cleanup of the generation pipeline**.

## External protocol integration

- **A2A 1.0 protocol**: adds an Agent Card discovery endpoint (`/.well-known/agent-card.json`) and a JSON-RPC endpoint (`/a2a`) supporting SendMessage / GetTask / CancelTask. A2A calls reuse the same generation pipeline as on-device chat (model resolution, tools, approval, memory); there is no second generation path.
- **Tasker integration**: adds a broadcast entry `io.zer0.muse.action.TASKER_TRIGGER` (extra `prompt`), allowing Tasker / MacroDroid and similar automation platforms to trigger a conversation turn whose result is stored and reported back via notification. Runs a single background turn without granting tool access.

## Plugin capability opening

- **Plugin hook system**: plugins may declare `hooks` in the manifest and export `onPromptFinalize(event)` from the entry script to rewrite history before the prompt is finalized. On failure or invalid return, the original history is kept (fail-open) and generation is not blocked.
- **Plugin sandbox opening**: fetch / XHR / WebSocket network access is enabled, bridge actions are fully allowed, and capability names are only format-checked. Two limits remain: page navigation is still blocked, and `file://` / `content://` reads remain isolated.
- **Signature compatibility**: fields participating in manifest signing are now an explicit allowlist; newly added fields do not participate by default, so existing signed plugin packages are unaffected.

## Troubleshooting capability

- **Diagnostic log export**: adds a generation-pipeline trace recorder that logs each turn's terminal state, duration, time-to-first-token, character counts, stream-end classification, finish_reason and compression trigger marks. Entry point: Settings → About → Export Diagnostics.
  Hard rule: no message body or API key is recorded; session identifiers are hash-redacted.
- **Sub-agent timeout**: the default timeout is raised from 120s to 240s, with a new `timeout_ms` parameter (30s–900s); timeout errors now carry progress information (rounds executed, tool calls made, whether a partial summary exists) so work can be resumed instead of redone.

## Generation pipeline cleanup

- Four blocks — the tool-call loop (including single-round streaming), context compression orchestration, tool approval, and pending tool-call resume — were extracted wholesale from the main view model into independent components, reducing the main file from 7483 to 5141 lines. **The migration is a pure extraction with unchanged behavior**, and it adds unit tests for compression counting and error classification.
- Fixes behavior divergence caused by compression thresholds being judged in multiple places, consolidated into a single source of truth; a total budget gate is added to the system prompt so long sessions no longer blow the budget from prompt bloat.

## Changes inherited from v2.5.7

- Fixes false context-overflow warnings: when a model does not report its window size, the fallback window is raised from 32K to 128K.
- Adjusts the hard-block threshold to leave margin for the difference between client-side estimation and server-side counting.

## UI and misc

- The side navigation rail is rewritten as a linear mapping by content height, with the thumb position corresponding to scrolled content; fixes the drag jump caused by long messages, with snapping added at both ends.
- Adds generative UI (progressive rendering + mobile adaptation + security hardening); adds browser screenshot capability so the model can see rendered pages.
- Fixes multimodal toggle failing to persist, the scroll-to-bottom button positioning, and glass-intensity presets.
- Database version 110, unchanged since v2.5.4; upgrading directly loses no data.

## Installation

- Universal: Muse_v2.5.8_universal.apk (any device)
- 64-bit: Muse_v2.5.8_arm64-v8a.apk (most modern phones)
- 32-bit: Muse_v2.5.8_armeabi-v7a.apk (older devices)
