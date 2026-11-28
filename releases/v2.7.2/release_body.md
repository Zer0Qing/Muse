# Muse v2.7.2

> 构建说明：本次发布使用此前构建并测试的 APK。构建时源码处于未提交状态，准确源码快照未留存；当前 tag 中的源码无法直接复现这些 APK。

> 本次更新扩展 Agent 的任务协作与长期跟进能力，加入 Linux 轻量云电脑、可选的抽屉式首页、产物与媒体工作台，并改进备份、记忆和知识库的可靠性。

## 一、Agent 与任务协作

- **任务状态与下一步建议**：在 Agent 对话中查看进行中任务、待处理事项和连接状态；建议会结合当前对话与实时任务生成，点击即可继续。
- **持续跟进与长期目标**：新增 Watcher 持续关注事项、Goals 长期目标和夜间整理；整理结果可用于次日回顾与建议。
- **更直接的任务协作**：可在对话中 `@` 队友分派任务；工具权限分为四档，需要确认的操作仍会逐次征求同意。断连或权限问题可从对话中的提示卡修复后继续。
- **MCP 连接体验改进**：完善服务添加、授权状态、工具浏览、聊天调用和结果呈现；支持分页读取工具目录与导入连接配置。
- **Spaces 互动小应用**：对话卡片可以收纳到 Spaces，卡片支持折叠、展开和与聊天双向互动；新增可拖动的侧边栏/悬浮窗及五子棋示例。
- **Linux 轻量云电脑**：通过 SSH 配对 Linux 主机，把脚本和环境任务交给云端运行，并在手机上查看或接管远程浏览器；连接时可核对 SSH 主机身份。

## 二、首页、导航与日常提醒

- **两种首页布局**：可选经典三页或极简抽屉首页；首次引导中可查看布局示例并选择，之后也能在外观设置中切换。
- **抽屉首页更好找**：侧栏整合会话搜索、助手/群聊/新任务切换和常用工具；问候下方可显示每日总结与提示。
- **主动消息支持预约**：可按指定时间安排后续提醒；到期后结合免打扰时段处理，并可在牵挂账本中查看安排和状态。

## 三、产物库与媒体创作

- **统一产物库**：网页、图表、代码、文档及创作工坊作品集中管理，支持搜索、分类筛选、缩略图、重命名、确认删除和返回来源对话。
- **全屏长文阅读器**：支持阅读进度、渲染/源码切换、换行设置、字号调整、代码行号与复制，以及复制、另存、分享和跳回原对话。
- **创作工坊整理**：生图、生视频和作品库分区展示；支持多图浏览、删除确认，以及视频保存和分享。
- **对话内容展示修复**：修复卡片重复显示、公式与表格布局问题；宽表格可横向滚动，图表和流程图适配深浅色主题。

## 四、语音、自动化与设备操作

- **语音交互优化**：改进按住说话输入和语音回复策略，减少误触发与异常中断。
- **自动化录制更清晰**：录制步骤会保留必要的应用和控件线索；自动化页显示绑定技能，并提供后台运行限制诊断与系统设置入口。
- **设备操作更易恢复**：改进无障碍与虚拟屏操作的审批、截图和实时接管流程。

## 五、备份、记忆与知识库

- **备份覆盖更多内容**：纳入助手头像、背景、语音、工作区、渠道会话、工具交付文件和应用保存的附件；支持更大的文件，并兼容旧版备份导入。
- **供应商密钥保护更明确**：密钥备份选项集中在外层；云端备份若包含密钥，必须设置备份密码。
- **恢复过程更可靠**：改进大文件读写和恢复回滚，降低部分数据失败时留下不完整配置的风险。
- **关于你独立管理**：用户画像可单独开关，按五个分域查看与清理，并与知识库索引分开保存。
- **记忆与知识库修复**：改进记忆去重、整理写入控制和记忆空间合并；收紧知识片段预算与会话附件隔离，改进错误反馈和批量删除。数据库升级到 v142，并支持旧版本迁移。

## 六、性能与设置

- **对话更轻快**：精简默认提示词和工具链，优化长对话的流式显示与滚动表现，减少无效模型调用。
- **设置更容易浏览**：解除一级设置页面的滑动距离限制，并调整部分选项的分类位置。
- **中英文混排优化**：改进中文、英文与数字混排的阅读间距和卡片正文排版。

---

# Muse v2.7.2 (English)

> Build note: This release uses the previously built and tested APKs. The build used an uncommitted source tree whose exact snapshot was not retained, so the tagged source cannot reproduce these APKs directly.

> This update expands Agent collaboration and follow-ups, adds lightweight Linux cloud computers and an optional drawer-style home, brings artifacts and media into dedicated workspaces, and improves backup, memory, and knowledge-base reliability.

## 1. Agent & Task Collaboration

- **Task status and next-step suggestions**: See active tasks, pending items, and connection status in Agent chat. Suggestions use the current conversation and live task state, and can be tapped to continue.
- **Follow-ups and long-term goals**: Watchers keep track of ongoing items, Goals support longer plans, and overnight organization can prepare material for the next day's review and suggestions.
- **More direct collaboration**: Assign work to teammates with `@`. Tool permissions have four levels, and actions that require approval continue to ask each time. In-chat recovery cards help resume after connection or permission issues.
- **Improved MCP connections**: Improved server setup, authorization status, tool browsing, chat calls, and result presentation. Tool catalogs can be paged, and connection configurations can be imported.
- **Interactive Spaces**: Save chat cards to Spaces, collapse or expand them, and interact with them from chat. Includes a draggable edge dock/floating window and a Gomoku example.
- **Lightweight Linux cloud computers**: Pair a Linux host over SSH to offload scripts and environment tasks, then view or take over its browser from the phone. SSH host identity can be verified during connection.

## 2. Home, Navigation & Daily Reminders

- **Two home layouts**: Choose the classic three-page layout or a minimal drawer layout. Onboarding includes previews, and the choice can also be changed later in Appearance settings.
- **A more useful drawer**: The drawer brings together conversation search, assistant/group/new-task switching, and common tools. Daily summaries and hints can appear below the greeting.
- **Scheduled proactive messages**: Schedule a follow-up for a specific time. Due reminders respect quiet hours and can be reviewed in the care ledger.

## 3. Artifacts & Media Creation

- **Unified artifact library**: Find chat webpages, charts, code, documents, and Media Studio creations in one place, with search, filters, thumbnails, rename, confirmed deletion, and source-chat navigation.
- **Full-screen document reader**: Includes reading progress, rendered/source modes, wrapping controls, font sizes, code line numbers and copying, plus copy, save, share, and return-to-chat actions.
- **Organized Media Studio**: Image creation, video creation, and gallery have separate sections. Browse multiple images, confirm deletions, and save or share videos.
- **Improved chat content display**: Fixed duplicate cards and sizing issues with formulas and tables. Wide tables scroll horizontally, while charts and diagrams adapt to light and dark themes.

## 4. Voice, Automation & Device Control

- **Voice interaction updates**: Improved push-to-talk input and voice reply behavior to reduce accidental activation and interruptions.
- **Clearer automation recording**: Recorded steps retain useful app and control details. The automation page shows linked skills and provides background-run diagnostics with links to system settings.
- **Easier device-operation recovery**: Improved approvals, screenshots, and live handoff for accessibility and virtual-display operations.

## 5. Backups, Memory & Knowledge Base

- **Back up more of your data**: Includes assistant avatars, backgrounds, voice recordings, workspace files, channel conversations, tool outputs, and attachments saved by Muse. Larger files are supported, and older backup formats remain importable.
- **Clearer provider-key protection**: The provider-key backup option is kept at the outer level. Cloud backups that include keys require a backup password.
- **More reliable restores**: Improved large-file transfers and restore rollback to reduce the chance of partially restored settings after a failure.
- **Manage About You separately**: User profiles can be switched off independently, reviewed and cleared across five domains, and are stored separately from knowledge-base indexes.
- **Memory and knowledge-base fixes**: Improved memory deduplication, write controls, and space merging. Tightened snippet budgets and per-chat attachment isolation, and improved error reporting and bulk deletion. The database is now at v142, with migrations for older versions.

## 6. Performance & Settings

- **Lighter conversations**: Trimmed default prompts and streamlined tool calls, while improving streaming and scrolling in long conversations to reduce unnecessary model usage.
- **Easier-to-browse settings**: Removed the top-level scroll-distance limit and reorganized selected settings.
- **Improved CJK and Latin typography**: Refined spacing for mixed Chinese, English, and numeric text in messages and cards.
