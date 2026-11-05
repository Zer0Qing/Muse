# Muse v2.6.22

> 这一版集中完善了设置页、主动消息、表情包与消息渠道的可用性，并修掉几类「界面上看起来能用、实际不生效」的问题。

## 重要修复

- **深色模式的菜单与底部面板不再是灰白块**：暗色下右上角「更多」菜单、底部选择面板与消息气泡恢复为深色玻璃；亮色模式观感不变。
- **内置免费额度换到新网关**：此前仍在使用旧地址的配置会自动改写，升级后无需手动重填；界面名称统一为「内置免费模型」。
- **流式回复半截中断不再干等**：出首字后若长时间没有新内容，会提示响应中断并保留已经显示的部分，不再转圈数分钟。
- **主动消息的「测试发送」与真实发送一致**：当只允许普通对话时，测试会复用现有对话，不再误报「没有可用会话」。
- **内置浏览器与卡片预览不再互相踢下线**：查看 HTML/SVG 卡片不会清空浏览器里其他网站的登录状态。
- **网页版的记忆、知识库、数据隐私三页不再空白**：时间标签与列表数据恢复正常；断线后只读页也不会一直转圈。
- **安全模式下退出更干净**：崩溃后再次启动时停止后台生成服务，不再附带一次「Unable to stop service」的二次崩溃；崩溃日志同时记录当时的堆用量。
- **清除全部聊天清得更彻底**：对话树快照、待办、附件索引、工具装载等残留一并清理，不再只清数据库里的消息。

## 新能力

- **搜索设置页**：多引擎 API Key 默认掩码，可逐行显示；「搜索顺序」可上下调整并立即影响实际检索；每个引擎都能单独「验证」，未填 Key 的不会发请求。
- **浏览器设置页**：可选择地址栏默认搜索引擎、是否接受 Cookies、一键清除站点数据；地址栏输入关键词会按所选引擎检索。
- **主动消息**：主动程度改为带档位名的轨道控件；新增「它该做与不该做的事」约定页；新增语音回复频率四档与音色入口；新增「每日保底」——距上一条超过设定时长会补一条，但免打扰、冷却、你刚说过话与当天额度仍然优先。
- **工作区多级菜单**：对话右上角「更多 → 工作区」可以逐层进入目录，并在当前位置直接打开。
- **表情包重做**：支持从相册一次多选、导入 PDF/Word/链接清单、重命名表情包、挪图、改配文、整包导出与导回、按原图链接批量下载；PDF 切格按真机标定，碎片格不再入库。
- **消息渠道**：新增跨渠道转发，只能发到渠道已绑定的接收方，并有单轮与每小时上限；微信渠道（WeClaw）换绑后会自动重建收消息的位置，不再出现「只能发不能收」。
- **辅助模型失败可见**：记忆抽取、摘要这类后台任务使用的辅助模型调用失败时，任务路由页会给出模型名与原因；聊天本身不受影响。
- **内存更省**：图片缓存预算下调，系统内存紧张时会主动释放可重建的图片缓存，用于减少低内存机型上的随机闪退。

## 体验改进

- 全站字型按角色收敛，标题、正文与强调文字的层级更一致；放大字号时强调文字会跟着变大。
- 无障碍自动化在坐标越界、屏幕内容读取不全、截图超限时都会给出具体原因，不再静默失败；截图超限会先降质量再缩尺寸。
- 插件总闸关闭后，斜杠命令与面板提交也不再执行插件代码；安装失败会说明具体原因。
- 小手机里发消息、解散群、清空记录、朋友圈操作等动作加了统一的异常兜底，出错会留下提示而不是直接把应用关掉。
- 记忆搜索支持中文连写（不打空格也能命中）；记忆标题与摘要不再丢段；同名记忆会被拒绝；矛盾清单不会覆盖坏文件。
- 审批等待期间，通知栏与任务卡会明确显示「等你批准」，不再误报「正在执行」；子 agent 超时文案改为可读的时长。
- 网页版命令在重连后不会重复执行；写入命令没有回应时只提示「未确认」，不会假装成功或擅自回滚。

## 说明

- 本版包含从 `v2.6.21` 之后累积的全部改动。
- 真机验收尚未完成；当前证据来自本机编译、单元测试与 APK 静态校验。
- 仓库内既有的静态检查存量债（ktlint / detekt / 结构收敛）仍为红，非本版引入。

---

# Muse v2.6.22 (English)

> This release focuses on the usability of settings, proactive messages, sticker packs, and messaging channels, and fixes several cases where the interface looked functional but the behavior never took effect.

## Important Fixes

- **Dark-mode menus and bottom sheets are no longer washed-out gray blocks**: the top-right "More" menu, the bottom selection sheet, and message bubbles return to dark glass in dark mode; light mode looks unchanged.
- **The built-in free quota now uses the new gateway**: configurations still pointing at the old address are rewritten automatically, so no manual re-entry is needed after upgrading; the UI name is now "Built-in free model".
- **A half-finished stream no longer leaves you waiting**: if no new content arrives for a long time after the first token, Muse reports an interrupted response and keeps the text already shown instead of spinning for minutes.
- **Proactive "test send" matches real delivery**: when only plain chats are allowed, the test reuses an existing chat instead of incorrectly reporting that no session is available.
- **The built-in browser and card preview no longer sign each other out**: viewing an HTML/SVG card no longer clears login state for other sites in the browser.
- **The web version's memory, knowledge base, and data-privacy pages are no longer blank**: timestamps and list data display correctly, and read-only pages stop spinning forever after a disconnect.
- **Cleaner shutdown in safe mode**: after a crash, stopping the background generation service no longer triggers a second "Unable to stop service" crash; the crash log also records heap usage at the time.
- **"Clear all chats" now clears more thoroughly**: conversation-tree snapshots, todos, attachment indexes, and loaded-tool state are removed together with the messages, instead of only the database rows.

## New Capabilities

- **Search settings**: API keys for multiple engines are masked by default and can be revealed per row; the search order can be reordered and immediately affects real queries; each engine can be verified on its own, and engines without a key send no request.
- **Browser settings**: choose the default search engine for the address bar, whether to accept cookies, and clear site data in one tap; keywords typed in the address bar are searched with the selected engine.
- **Proactive messages**: the intensity control is now a track with named levels; a new "things it should and should not do" page; four voice-reply frequency levels and a voice-tone entry; and a "daily guarantee" that sends one message after the configured interval — quiet hours, cooldown, recent user activity, and the daily budget still take priority.
- **Multi-level workspace menu**: "More → Workspace" in the chat header lets you drill into folders level by level and open the current location directly.
- **Sticker pack overhaul**: multi-select from the gallery, import from PDF/Word/link lists, rename packs, move images, edit captions, export and re-import a whole pack, and batch-download from original links; PDF grid cutting is calibrated on real devices and fragmented cells are no longer imported.
- **Messaging channels**: cross-channel relay is limited to the recipient already bound to the channel, with per-turn and hourly caps; after re-binding, the WeClaw channel rebuilds its receive position automatically instead of getting stuck at "can send but cannot receive".
- **Visible helper-model failures**: when the helper model used for memory extraction or summaries fails, the task-routing page shows the model name and reason; chat itself is unaffected.
- **Lower memory usage**: image-cache budgets are reduced, and rebuildable image caches are released when the system is under memory pressure, aimed at fewer random crashes on low-memory devices.

## Experience Improvements

- Typography is consolidated around roles, so headings, body text, and emphasis are more consistent; emphasis text now scales with the font-size setting.
- Accessibility automation now reports a concrete reason for out-of-range coordinates, incomplete screen reads, and oversized screenshots instead of failing silently; oversized screenshots are first compressed and then downscaled.
- When the plugin master switch is off, slash commands and panel submissions no longer execute plugin code; installation failures explain the specific reason.
- In Mini Phone, sending messages, disbanding groups, clearing history, and Moments actions now share an exception guard: failures leave a visible message instead of closing the app.
- Memory search supports unspaced Chinese text; memory titles and summaries no longer lose sections; duplicate memory names are rejected; the contradiction list no longer overwrites a corrupt file.
- While an approval is pending, the notification and task card clearly say "waiting for your approval" instead of falsely reporting "running"; sub-agent timeout messages now use readable durations.
- Web commands are no longer executed twice after a reconnect; when a write command gets no response, the UI says "unconfirmed" rather than pretending success or rolling back on its own.

## Notes

- This release includes all changes accumulated since `v2.6.21`.
- On-device acceptance is not complete yet; current evidence comes from local builds, unit tests, and static APK verification.
- Pre-existing static-check debt in the repository (ktlint / detekt / structure convergence) remains red and was not introduced by this release.
