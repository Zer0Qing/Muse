Muse v2.6.1。

> 本版覆盖自 v2.6.0 发布以来的全部改动，以修复为主，并新增内置 Python 运行时。

## 新功能

- **内置 Python 运行时**：应用内直接运行 Python 3.14（完整标准库：ssl/sqlite3/zlib/hashlib/json/re 等；文件读写、网络），无需安装 Termux 或任何外部应用。助手可用新工具 `execute_python_script` 直接执行 Python 脚本，支持 pip 安装纯 Python 第三方包；插件也可选择用 Python 编写（manifest 的 `runtime` 字段）。
- **全局「强制返回思考」开关**（设置 → 聊天）：开启后不再受助手默认设置约束，只要模型支持推理就返回思考过程。
- **主动消息「全天开放」**（设置 → 主动消息 → 免打扰时段）：新增开关。此前只能用起止小时表达允许发送的区间，凑不出完整的 24 小时（设 23-23 反而一条都不发），想要「随时都能收到」时没有可选项。打开后 24 小时都可能收到主动消息。

## 重要修复

- **思考过程泄漏到正文**：修复部分推理模型（如 DeepSeek 系）在工具调用场景下把思考链当正文显示、且「显示思考过程」开关关不掉的问题。保留「不产生空白回复」的底线。
- **无障碍权限误报**：修复「系统已开启无障碍、权限向导显示已开启，但助手调用工具时却报权限未打开」——权限状态改为实时刷新，不再读启动时的旧缓存。
- **选中文字崩溃**：修复在任意界面选中文字、点系统浮动菜单「处理文字」时闪退的问题。
- **首页每日总结被截断**：总结不再被压缩成两行省略，改为完整展示、自然换行；长度由生成侧控制。
- **记忆流长文无法预览**：记忆时间轴里的长记忆现在可以直接点开查看全文，不必进编辑页。
- **Fish Audio 语音克隆**：修正为官方端点与字段，此前即便 API Key 正确也稳定报错。
- **供应商编辑串台**：修复连续编辑不同供应商时，上一个的 API Key 等状态被带到下一个。
- **API Key 粘贴校验**：密钥里混入中文等非法字符时给出可读提示，替代原先难懂的报错。
- **Codex 登录**：补全请求头标识与模型清单解析。
- **看记忆库时闪退**：修复在「全部」作用域浏览记忆时偶发闪退——主助手与各子助手的记忆分别存放、各自编号，同编号的条目会撞车导致列表无法渲染。
- **打开应用时偶发闪退**：修复系统「关闭动画」（开发者选项/省电模式把动画时长设为 0）时，页面切换过程中偶发闪退——动画时长被压到 0 会让切换进度计算出现非法值。
- **快捷记录悬浮条**：修复悬浮条展开后输入框有时收不到焦点、软键盘不弹出的问题。
- **自动备份现在包含设置**：此前每日自动备份只打包会话/记忆/知识库数据，从「最近备份记录」一键恢复后会丢设置；现在与手动导出、云备份一致，设置项也一并备份、一并恢复（仍不含供应商密钥等敏感凭据）。

## 界面与体验

- 弹窗、底部弹层、分组标题、列表行、多图网格等公共组件统一整理：弹窗更紧凑、遮罩不再压暗导航区域、分组标题对齐修正、长标题不再挤爆布局、多图改为方形缩略网格。
- 图片移除按钮统一为共享组件（保留足够的触摸区域）。
- **自己发的消息可以直接删**：用户消息的快捷栏补上删除按钮（此前只有复制/重试/编辑，删除藏在长按菜单里），删除时会连同它的回复一并处理，并提供「仅此条 / 连同后续」选择。
- **助手跑 Python 用错了路子**：让助手用 Python 时，它会跑去命令行里找 `python` 解释器、翻目录，反复空转。内置运行时的正确入口是专用工具，之前那一入口没有随请求发送给模型，导致它根本调不到；现已修好并写死引导（这是运行 Python 的唯一入口，不要再去找解释器）。
- **一段 JavaScript 执行必失败**：内置 JS 沙盒的一个状态表用 null 作键（部分数据结构不允许），导致所有内置 JS 执行调用稳定报错。已修复。
- **工具检索后仍调不到**：用工具库检索到一个工具后，它会说“下一轮可用”，但真调用时却报「未在本轮暴露」。原因是检索范围没考虑助手自身的工具配置，于是对配置外的工具空头承诺。现在检索只返回本助手真正可用的工具，命中不到时直接告知去助手设置里启用。
- **计划卡 / 执行卡下方冒出快捷按钮**：在「消息快捷按钮」里新增的自定义动作，之前会出现在任务卡（计划卡 / 执行卡）下方。这些位置本就不该有消息快捷按钮，现在已排除。

## 说明

- 本版以修复和稳定性为主，建议升级。
- 同时清掉了一批不再使用的内置资源，安装包体积相应减小。

---

# Muse v2.6.1 (English)

> This release covers all changes since v2.6.0, focused on fixes, plus a new embedded Python runtime.

## New

- **Embedded Python runtime**: Run Python 3.14 directly inside the app (full standard library: ssl/sqlite3/zlib/hashlib/json/re; file and network access), with no need to install Termux or any external app. Assistants can use the new `execute_python_script` tool, install pure-Python packages via pip, and plugins can opt into Python (manifest `runtime` field).
- **Global "always request reasoning" switch** (Settings → Chat): when enabled, reasoning is requested whenever the model supports it, regardless of assistant defaults.
- **"All day" for proactive messages** (Settings → Proactive messages → Quiet hours): new switch. The allowed window could only be expressed as a start/end hour range, which cannot cover a full 24 hours (23-23 would block every message), so there was no way to say "any time is fine". Enable it to allow proactive messages at any hour.

## Fixes

- **Reasoning leaking into the reply body**: fixed reasoning chains being shown as message content for some reasoning models (e.g. DeepSeek family) during tool calls, where the "show reasoning" switch could not hide them. The never-empty reply guarantee is preserved.
- **Accessibility permission misreport**: fixed "system shows accessibility enabled and the wizard agrees, but tools report permission not granted" by refreshing permission state live instead of reading a stale startup cache.
- **Crash on selecting text**: fixed a crash when selecting text anywhere and tapping "Process text" in the system floating menu.
- **Home daily summary truncated**: summaries are no longer squeezed into two ellipsized lines; length is controlled at generation time.
- **Memory timeline long entries**: long memories can now be expanded in place instead of only inside the editor.
- **Fish Audio voice cloning**: corrected to the official endpoint and fields; previously failed even with a valid API key.
- **Provider edit state bleed**: fixed state (such as API key) from one provider carrying over to the next when editing sequentially.
- **API key paste validation**: readable message when the key contains non-ASCII characters, replacing the previous cryptic error.
- **Codex sign-in**: added request headers and model list parsing.
- **Crash while browsing memories**: fixed an occasional crash when viewing memories in the "All" scope — main and sub-assistant memories are stored separately with independent numbering, and same-numbered entries collided so the list could not render.
- **Occasional crash on app open**: fixed an occasional crash during screen transitions when system animations are turned off (developer options / battery saver setting animation duration to 0) — zero-length animations produced an invalid transition progress value.
- **Quick capture overlay**: fixed the input sometimes not receiving focus so the keyboard did not appear when the overlay expanded.
- **Automatic backup now includes settings**: the daily automatic backup previously packaged only conversations, memories and knowledge data, so restoring from "recent backups" lost your settings. It now backs up and restores settings too, matching manual export and cloud backup (provider secrets and similar credentials are still excluded).

## UI and polish

- Unified dialogs, bottom sheets, section labels, list rows and multi-image grids: tighter dialogs, scrim no longer dims the navigation area, corrected section label alignment, long titles no longer break the layout, square thumbnail grid for multiple images.
- The image remove button is now a shared component with an adequate touch target.
- **Delete your own messages directly**: the quick action row for user messages now includes delete (it previously only had copy / retry / edit, with delete hidden in the long-press menu). Deleting also removes its reply, and offers "only this one / with following".
- **Assistant took the wrong path to run Python**: asked to run Python, the assistant would hunt for a `python` interpreter on the command line and scan directories, spinning without result. The embedded runtime has one dedicated tool as its entry point, but that tool was not being sent to the model, so it could not be called at all; this is fixed, with guidance pinned down (it is the only entry point — stop looking for an interpreter).
- **A JavaScript execution path always failed**: an internal JS sandbox state table used a null key (not allowed by the data structure), so every built-in JS execution call failed consistently. Fixed.
- **A tool found via search still could not be called**: after searching the tool library for a tool, it would say "available from your next call", but the actual call failed with "not exposed in this turn". The search range ignored the assistant's own tool configuration, so it promised tools that were configured off. Search now returns only what the assistant can actually use, and says so plainly when a tool needs to be enabled in assistant settings.
- **Quick action buttons appearing under plan/execution cards**: custom actions added in "message quick actions" used to show up under task cards (plan / execution cards). Those spots should never carry message quick actions; they are now excluded.

## Notes

- This release is primarily fixes and stability; upgrading is recommended.
- A batch of unused built-in resources was removed, making the install package smaller.
