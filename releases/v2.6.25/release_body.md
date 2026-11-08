# Muse v2.6.25

## 修复

- 修复了在文件夹里点「添加已有对话」会立刻闪退的问题。
- 修复了在快速记录里设置提醒会闪退的问题。
- 修复了 Shizuku 授权后一直显示「已授权但 Shell 服务未就绪」、点「刷新状态」也没用的问题；在部分手机上会偶发反复连不上。
- 修复了点「授权 Muse」不弹出授权框、Shizuku 里也看不到请求的问题，点击后会立刻提示「请求已发送」。
- 修复了 Shizuku 状态页要等十几秒才显示就绪的问题，现在打开就能看到。
- 修复了 Termux 扩展渠道要等很久才返回结果、偶发返回 200 却什么反馈都没有的问题。
- 修复了设备命令执行失败时助手拿不到原因、会把同一条命令原样重发一遍的问题，现在会说明失败原因和下一步该做什么。
- 修复了权限向导里 Termux 一栏不显示失败原因的问题。
- 修复了启动时整段插件装配失败、导致部分后台任务不执行的问题。
- 修复了没配置可用的模型服务时，发消息会空转约一分钟却什么都不出现的问题，现在会直接说明原因并给出设置入口。
- 修复了卡片里的按钮全部点不动的问题。
- 修复了翻译子菜单点「返回」会关掉整个菜单、只能重新长按两轮找回位置的问题。
- 修复了委托内容点一下就直接发送的问题，现在会先停在输入框，由你决定发不发。
- 修复了设置里搜到开关类条目却只跳到分类页、找不到开关的问题，现在会滚动定位并短暂高亮。
- 修复了任务页「已归档」「新建文件夹」「多选编辑」入口缺失、归档后无法恢复的问题。
- 修复了已发送出去的文字又回到输入框的问题。
- 修复了删除供应商、删除云端备份、解绑 WeClaw、批量删除会话不先确认就执行的问题。
- 修复了媒体设置和渠道编辑里未保存的内容离开时被静默丢弃的问题，现在会先询问。
- 修复了渠道长表单在键盘弹出后够不到「保存」按钮的问题。
- 修复了分享二维码默认带上 API Key 的问题，现在默认不含，需要时显式开启并会提示风险。
- 修复了用户气泡颜色发浑、像糊了一层的问题，现在颜色随主题走、更清晰。
- 修复了设置头像后聊天气泡仍只显示名字首字的问题。
- 修复了同一条会话在首页、聊天顶栏、搜索和备份里显示不同标题的问题。
- 修复了设置页小标题贴住上方卡片、部分说明文字颜色太淡在深色模式下几乎读不清的问题。
- 修复了几处文案和翻译问题：状态提示漏出代码里的英文词、主题名被误译成疾病名、部分语言里「1 条」却用复数名词。
- 修复了定时任务列表显示一个假日期的问题。
- 修复了停用的助手看起来像被删除的问题，现在会标注「已停用」。
- 修复了命令面板里空标题会话显示一串无意义编号的问题。
- 修复了调整语速、音高时每拖一下都写一次设置、容易卡顿和互相覆盖的问题。
- 修复了工具截图把一长串乱码塞进对话、以及没做过文字识别却说「没识别到文字」的问题。
- 修复了通知权限在冷启动第一帧就弹出请求的问题，现在只在真正需要时提示一次。
- 修复了引导流程里选中没有语言资源的选项等于没选、按系统返回会直接退出应用的问题，现在返回是回到上一步、重进接着上次那一步继续。
- 修复了助手页的一批细节：简介为空会截断提示词、默认助手重复出现一行、两组分享同名、导入入口没有标签、头像不能点开看大图等。

---

# Muse v2.6.25 (English)

## Fixes

- Fixed a crash when tapping "Add existing conversation" inside a folder.
- Fixed a crash when setting a reminder from Quick Capture.
- Fixed Shizuku staying on "authorized but shell service not ready" with Refresh doing nothing; on some phones it could intermittently fail to connect again and again.
- Fixed "Authorize Muse" showing no dialog and no request visible in Shizuku; tapping now immediately confirms the request was sent.
- Fixed the Shizuku status page taking over ten seconds to show ready; it now shows as soon as you open it.
- Fixed the Termux channel taking a long time to return, and intermittently returning 200 with no feedback at all.
- Fixed the assistant retrying the very same command when a device command fails with no explanation; it now states what went wrong and what to do next.
- Fixed the permission wizard's Termux row never showing the failure reason.
- Fixed a startup failure that broke the whole plugin setup and skipped some background tasks.
- Fixed sending a message with no usable model service spinning for about a minute and producing nothing; it now explains why and offers a shortcut to settings.
- Fixed buttons inside cards being unclickable.
- Fixed "Back" in the translate submenu closing the whole menu, forcing two long-press rounds to find your place again.
- Fixed delegation sending immediately on tap; the assembled text now stays in the input box for you to decide.
- Fixed settings search landing on a category page when the result was a toggle, leaving you unable to find the switch; it now scrolls to the row and briefly highlights it.
- Fixed missing entries for Archived, New folder and multi-select edit on the task page, which made archived items impossible to restore.
- Fixed sent text reappearing in the input box.
- Fixed deleting a provider, deleting a cloud backup, unbinding WeClaw and bulk-deleting conversations without any confirmation.
- Fixed unsaved edits in media settings and channel editing being silently discarded on leave; you are now asked first.
- Fixed the Save button being unreachable in long channel forms once the keyboard opened.
- Fixed QR sharing including the API key by default; it is now omitted unless you explicitly turn it on, with a warning.
- Fixed user bubbles looking washed out; they now use clear colors that follow the theme.
- Fixed chat bubbles showing only a name initial even after an avatar was set.
- Fixed the same conversation showing different titles on the home screen, chat header, search and backup export.
- Fixed settings sub-headings sitting flush against the card above, and faint secondary text being nearly unreadable in dark mode.
- Fixed several wording and translation defects: code-level words leaking into status labels, a theme name translated as a disease, and "1" paired with a plural noun in some languages.
- Fixed the scheduled-task list showing a made-up date.
- Fixed disabled assistants looking deleted; they are now labelled as disabled.
- Fixed the command palette showing a meaningless ID for conversations with an empty title.
- Fixed voice rate and pitch writing a setting on every drag step, causing stutter and overwritten values.
- Fixed tool screenshots dumping a long blob of gibberish into the chat, and claiming "no text found" when text recognition was never attempted.
- Fixed the notification permission prompt appearing on the very first frame of a cold start; it now appears only when actually needed, once.
- Fixed onboarding offering a language with no translations (selecting it did nothing) and system Back quitting the app; Back now goes to the previous step and re-entering resumes where you left off.
- Fixed a batch of assistant-page details: an empty introduction truncating the prompt, the default assistant listed twice, two share groups sharing a name, the import entry having no visible label, and the avatar not opening full size.
