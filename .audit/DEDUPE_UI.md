# 链路 5：UI / 设计系统 / 可访问性 — 去重基线

## 已确认修复/设计决策（不得再报）
- LAYOUT-01 行内文本取宽+限行：护栏 check_row_text_width.py 零容忍基线（空基线）；MarkdownText 豁免是设计意图；共享行组件两件套齐。
- CMP-11 组件收敛护栏生效；M3 直接引用只降不升。
- 工具消息 reasoning 显示、ToolRunCard 组内思考完整复制、纯 reasoning 不计"执行了 N 个操作"、恢复态 PlanCard 双卡抑制已修。
- TaskCard 单步暂停/恢复（toolCallId 绑定、PENDING 才可暂停、RUNNING 不强杀）。
- 工具结果 diff/table UI 不再静默丢尾部。
- MessageBubble 新增 showToolCallDetails 的 LongParameterList 按点 @Suppress（屏幕级组装点口径）。
- AssistantAvatar 按显示尺寸解码（OOM 修复，AssistantAvatarMemoryWiringTest）。
- ChatListScreen 撤回预览 MuseIcons.chat + MuseIconSizes.iconTiny。
- TerminalScreen 5 处 M3 IconButton → MuseTopBarIconButton；OnboardingScreen M3 Switch → MuseSwitch。
- 更新横幅是纯文字 Surface 是设计如此（非回归）。
- MuseDialog 内容区已滚动（勿再嵌套滚动容器）——已知陷阱。
- MuMu 模拟器 insets≈0 用 museSafeTopInsetPadding 兜底——已知。
- 输入栏重设计 + 玻璃质感 + MessageMapBar 跳转条 + 导航条拖动跳位修复（v2.5.x 批次）已落地。
- TalkBack/对比度需设备验证（已知）；五个语言包各缺 101 翻译（已知）。

## 本链路重点探查方向
1. **2026-10 拆分重构后的 UI 状态一致性**：ChatViewModel 拆出 coordinator/transformer 后，UI 状态流（StateFlow/LiveData）有没有重复发射、丢失更新、跨会话串状态（此前串会话类 bug 修过多轮——查新提取代码是否复现同类模式）。
2. v2.5.x 新界面（输入栏重设计、MessageMapBar、玻璃质感面板、诊断导出入口、A2A 设置页?）：是否遵守 LAYOUT-01（取宽+限行）、7 语言字符串、MuseIcons、Muse 组件、触达目标 48dp。
3. Compose 性能：新界面在长列表/大文本下的重组热点；stable/immutable 标注缺失导致的过度重组。
4. 深色主题/动态取色下新组件的颜色 token 合规（check_design_tokens 只查裸色——查新文件）。
5. 键盘/输入法交互：输入栏重设计后的 IME inset、表情面板/工具面板切换时聊天列表跳变。
6. 无障碍语义：新交互组件的 contentDescription/stateDescription（护栏只查"缺失"，查"语义错误"需要人工）。
