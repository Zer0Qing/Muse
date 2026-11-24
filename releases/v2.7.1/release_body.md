# Muse v2.7.1（紧急修复）

> **紧急修复版**：修复运行环境装不上的问题。v2.7.0 及更早版本在安装或修复运行环境时会失败，导致脚本类工具与本地语音识别不可用。本版已修复，建议尽快升级。

## 一、修复了什么

- **运行环境装不上**：安装 Ubuntu + Node 运行环境时，解包会停在 rootfs 自带的绝对符号链接上，报「符号链接目标越界」，安装直接中断。现在这类标准链接正常放行。
- **个别链接失败不再拖垮整次安装**：链接因权限或文件系统限制建不出来时，只跳过并继续，装完由自检判断是否可用。以前这种情况会让整次安装失败。
- **备份导入可以选方式**：支持「叠加合并」或「覆盖恢复」，导入完成后给出逐项结果摘要。
- **备份导出带上供应商密钥**：换机恢复后供应商配置直接可用，不用重新填 Key。
- **删除助手时的记忆提示分档**：按实际影响给出不同说明，不再是一句笼统警告。

> 修复前请先别点「更新运行环境」或「修复运行环境」。已经装好环境的用户升级后不受影响，也不需要重装。

---

## 附：v2.7.0 更新内容

> 本次升级带来全新「对话优先」的 Agent 体验、支持 DeepSeek 官方数据导入、全面翻新了设置与界面视觉，并修复了多项影响日常使用的稳定性问题。

### 一、全新 Agent 体验（默认开启）

- **像聊天一样驱动一切**：Agent 现已默认开启。可以直接用自然语言让它设提醒、安排周期任务（如「每天早 9 点整理 AI 早报」），确认轻卡片后自动生效，不需要手填复杂规则。
- **前台更干净**：Agent 对话默认隐藏思考过程、心情卡片和任务计划卡，保持纯粹的聊天体验（可在「设置 → Agent → Agent 对话显示」中随时按需开启）；任务进度收纳在右上角电脑图标角标里。
- **后台结果自然汇报**：后台任务完成或需要你做决定时，会先交给助手用自己的人设整理后再回复你；点开计划详情也可以直接补充决定并继续执行。
- **语音与心情同步补齐**：Agent 支持与单聊一致的本地 / 云端语音识别和助手心情展示；审批卡片前置人话说明，原始参数默认折叠。
- **沉浸式顶部布局**：首页三段胶囊导航栏与 Agent 居中悬浮头像均采用透明镂空设计，滑动消息时不再被顶栏遮挡。

### 二、新功能与体验升级

- **支持 DeepSeek 官方数据导入**：在「设置 → 数据导入」可直接导入 DeepSeek 官方导出的 `.zip` 对话记录。
- **设置页全面焕新**：全部设置页统一为更清爽的卡片分组样式，精简冗余分组与说明，新增更顺手的时间选择器与搜索栏。
- **全应用中文文案与多语言优化**：统一清理了开发术语与混用词（统一为「模型供应商」「运行环境」「技能」等），并补齐校对了西班牙语本地化。
- **统一思考过程面板**：单聊、Agent、群聊共用全新的可折叠思考面板，支持流式预览与耗时记录。
- **更克制的默认设置**：液态玻璃效果与预测性返回手势改为默认关闭（已开启过的配置不受影响）；普通对话默认不再每轮自动拼接知识库，仅在 `@` 指定文档或模型主动检索时引用。

### 三、重要修复

- **修复部分 Android 13+ 设备闪退**：根治开启液态玻璃后切换 Agent 或群聊页直接闪退的问题。
- **修复设置页开关点击无反馈**：修复联网搜索、后台保活、主动消息等开关点击后界面不刷新的问题。
- **修复主动消息通知跳转错位**：点击指向 Agent 会话的主动消息通知现在会直接回到 Agent 页，不再被当成单独对话打开。
- **修复中转站模型与绘图参考图误判**：中转站模型名支持请求透传与能力自动识别；未收录的绘图模型不再被误判为「不支持参考图」。
- **修复窄屏 / 分屏与多项点击崩溃**：修复小窗 / 窄屏下会话点不开、快捷胶囊消失、更新运行环境弹窗崩溃、超长文本分享与大段 HTML 预览闪退、空态导航锁死等问题。
- **运行环境下载加速与备份修复**：内置运行环境下载源已切换为更稳定的国内镜像，并修复跨机备份恢复与记忆中心「全部」域归属问题。

---

# Muse v2.7.1 (English)

> **Emergency fix**: resolves the runtime environment failing to install. On v2.7.0 and earlier, installing or repairing the runtime could fail, disabling script tools and local speech recognition. Fixed in this release; upgrading is recommended.

## What's fixed

- **Runtime environment could not install**: unpacking the Ubuntu + Node runtime stopped at absolute symlinks shipped inside the rootfs with "symbolic link target escapes root", aborting the install. These standard links are now accepted.
- **A single failed link no longer aborts the install**: when a link cannot be created due to permissions or filesystem limits, it is skipped and the install continues; the post-install self-check decides whether the environment is usable. Previously this failed the whole install.
- **Backup import now offers a mode**: choose "merge" or "replace", with a per-item result summary afterwards.
- **Backup export includes provider keys**: after restoring on another device, provider configuration works without re-entering keys.
- **Tiered memory warnings when deleting an assistant**: consequences are described by actual impact instead of one generic warning.

> Please avoid tapping "Update runtime" or "Repair runtime" before upgrading. Users with a working runtime are unaffected and do not need to reinstall.

---

## Appendix: What's New in v2.7.0

> This release brings a new chat-first Agent experience, DeepSeek official data import, a refreshed settings and UI design, and multiple stability fixes.

### 1. New Agent Experience (Enabled by Default)

- **Drive everything through chat**: Agent is now enabled by default. Ask in natural language to set reminders or schedule recurring tasks (such as a daily 9 AM AI briefing), confirm the lightweight card, and it runs automatically with no manual rule forms.
- **Cleaner foreground**: Agent chat hides thinking panels, mood cards, and inline plan cards by default to keep conversations focused (each can be toggled anytime in Settings → Agent → Agent chat display); task progress lives in the top-right computer badge.
- **Natural background reporting**: When a background task finishes or needs your decision, the result is handed back to the assistant to reply in character; opening a plan's details also lets you add your decision and resume directly.
- **Speech and mood parity**: Agent now shares local and cloud speech recognition and assistant mood cards with regular chat; approval cards lead with plain-language intent while collapsing raw parameters by default.
- **Immersive top layout**: The three-segment home pill bar and centered floating Agent avatar use transparent cutout styling so messages scroll smoothly underneath.

### 2. New Features & Improvements

- **DeepSeek official export import**: Import official DeepSeek `.zip` chat exports directly from Settings → Data Import.
- **Refreshed settings**: All settings screens now share a unified card-group layout with fewer redundant groups, cleaner descriptions, and new time picker and search controls.
- **Copy cleanup & localization**: Cleaned up technical jargon and inconsistent terms across Chinese UI copy, and completed Spanish localization proofreading.
- **Unified thinking panel**: Regular chat, Agent, and group chat now share a collapsible thinking panel with live preview and elapsed duration.
- **Restrained defaults**: Liquid Glass and predictive back gestures now default to off (existing user choices are preserved); regular chat no longer auto-injects knowledge base snippets every turn unless referenced with `@` or retrieved by the model.

### 3. Important Fixes

- **Fixed Android 13+ crashes**: Resolved native crashes when switching to Agent or group chat with Liquid Glass enabled.
- **Fixed unresponsive settings switches**: Fixed switches such as web search, background keep-alive, and proactive messaging failing to update visually after tapping.
- **Fixed proactive notification routing**: Tapping a proactive notification for an Agent session now opens the Agent tab directly instead of a standalone chat screen.
- **Fixed relay model matching & reference image detection**: Relay model names now pass through verbatim while still matching catalog capabilities; unlisted image models are no longer wrongly marked as unsupported for reference images.
- **Fixed narrow-window & crash issues**: Fixed unreachable chats and missing quick capsules in narrow windows, runtime picker crashes, long-text share crashes, large HTML preview crashes, and navigation lockups.
- **Faster runtime downloads & backup fixes**: Updated built-in runtime mirrors for faster downloads, and fixed cross-device backup restore and memory scope attribution.
