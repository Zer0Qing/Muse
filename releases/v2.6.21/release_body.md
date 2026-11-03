# Muse v2.6.21

> 这一版重点解决了主对话在长列表滚动、消息渲染和流式更新时的卡顿，并收紧了主动消息的落点与授权边界。

## 重要修复

- **主对话滚动更顺畅**：长消息不再一次性构建全部内容，消息头、正文与操作区拆分后按需渲染；流式过程减少重复计算和整页重组。
- **主动消息不再污染任务对话**：日常问候默认进入 Agent 会话，普通任务对话默认不接收主动消息；单个对话的静音开关只作用于当前对话。
- **主动消息通知回到正确记录**：点击不同时间发出的通知时，不再误把同一会话里后来的一条记录标记为已读。
- **进入对话不再自动弹出键盘**：进入聊天页先停留在正常界面，只有点击输入框后才开始输入。
- **Agent 自主操作更可控**：只在用户明确指定的任务、应用、动作和时段内自动执行读屏、点击或滑动；输入、发送、付款和删除等动作仍需审批。

## 新能力

- **按时段自主使用无障碍**：可以配置允许时段、应用包名、动作白名单与每日次数。只有当前任务得到明确确认且前台应用匹配时才会自动执行。
- **专项测试发送**：主动消息测试只发往 Agent 会话，不会为了测试而借用一个任务对话。

## 体验改进

- 对话长列表的滚动位置、消息渲染缓存与流式节流进一步收敛，降低滑动期间的布局和重组压力。
- 主动消息、会话静音和 Agent 会话设置的说明更清楚地反映实际行为。
- 本版从 GitHub 最新正式版 `v2.6.18` 之后持续累积改动，统一在本版提供。

---

# Muse v2.6.21 (English)

> This release focuses on smoother long-conversation scrolling and message rendering, alongside tighter proactive-message routing and authorization boundaries.

## Important Fixes

- **Smoother main-conversation scrolling**: long messages are no longer built all at once; headers, bodies, and actions render on demand, while streaming avoids repeated calculations and full-page recomposition.
- **Proactive messages no longer pollute task chats**: daily greetings default to Agent conversations, whereas ordinary task chats are opted out. A per-chat mute affects only that chat.
- **Proactive notifications resolve the correct record**: opening a notification no longer marks a newer proactive message from the same chat as read.
- **Entering a chat no longer opens the keyboard automatically**: the normal chat view appears first, and the input field gains focus only when tapped.
- **More controlled autonomous Agent actions**: screen reading, tapping, and scrolling can run automatically only for an explicitly confirmed task, app, action, and time window. Input, send, payment, and delete actions still require approval.

## New Capabilities

- **Scheduled autonomous accessibility**: configure an allowed time window, app package, action allowlist, and daily limit. Automatic execution occurs only when the current task is explicitly confirmed and the foreground app matches.
- **Dedicated proactive-message test delivery**: test messages go only to Agent conversations and never borrow a task chat.

## Experience Improvements

- Long-list scroll position, message render caching, and streaming throttling are tightened to reduce layout and recomposition pressure while scrolling.
- Proactive-message, per-chat mute, and Agent-session setting descriptions now reflect their actual behavior.
- Changes accumulated after the latest public GitHub release, `v2.6.18`, are included together in this release.
