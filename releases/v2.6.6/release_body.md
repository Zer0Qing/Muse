# Muse v2.6.6

> 卡片交互修了几处、代理不再半通不通，另外有些功能以前只有入口、点进去是空的。

---

## 中文

### 卡片

点卡片里的按钮有反应了。之前有几条路径根本没接通，控件画出来了，点下去什么都不发生。

还有两件事：卡片里不再莫名其妙出现一条滚动条；生成过程中卡片会先出现，不用等整段写完。

卡片现在知道自己是谁，能读回绑定的数据，回传时也能说清来自哪张卡。

**一个变化你可能会注意到**：设置里的「偏好卡片输出」默认关掉了。关着的时候，模型不会主动把表格、对比、选项框做成卡片，就正常写字。想让它做卡片的，去 设置 → 聊天 打开。

卡片本身还在改，这版不是它的最终样子。

### 代理

以前配了代理，有些请求走、有些绕过去；改完还得重启一次才生效。

现在所有出网请求都跟着代理走，改了下一次连接就是新的，不用重启。可以指定哪些地址不走代理。供应商页的「测试连接」能直接告诉你代理通不通。

### 一些以前点进去是空的地方

- **经验库、AI 理解卡、群聊记忆** 三个页签以前只有实现、没有入口，现在能进去了
- **快速笔记的附件**：指示灯从来不亮，因为添加和删除这两个动作之前没接上
- **桌面小组件**：选哪个助手都没用，一直用默认的；现在会用你选的那个
- **「快速语音输入」小组件**：以前点它只新建一个会话，不会开麦
- **角色关系**：备份时只存不还原，现在能来回
- **定时任务的条件**：设了条件但实际没生效

### 工具调用

模型碰到工具不通时，以前只能看到一个看不懂的报错。

- 工具执行前会先看通道通不通，不通就直接说要怎么开通
- 失败时会说明是网络问题还是参数问题、能不能重试
- 还会带上「第几次、还剩几次、该不该继续」，避免它死磕或者过早放弃
- 提示词里写的工具数量跟实际一致了，以前会写一个对不上的数
- 界面里能查工具地图，知道现在有哪些能用

### 记忆

跨会话能搜历史原文了，按助手分开。

新会话以前会收到一段「四段全是暂无」的空壳，现在不塞了。

你对它说的「以后别这样」会记成硬规矩，不只是当轮管用。

### 其他

- 收藏支持多选批量
- 主动消息以前可能一条都不发
- 备份漏了一种数据类型；角色关系补上还原
- 余额不足（402）会正确说成余额问题，不再当成网络错误反复重试
- 批量翻译会遵守术语表和自定义风格，跟单条翻译口径一致
- 语音的错误提示统一了，以前一个功能四种说法
- 群聊里工具参数被重复拼接，导致角色不吭声
- 没配渠道时不再每 5 秒打一条日志
- 会话待办会存下来，切会话不会丢
- 模型能读你亲手选的文件了
- 大段粘贴可以变成文件；相册支持批量选

### 等待动画

等待时的指示器换成一个会变形的墨色图形（叶 → 星 → 环），各个位置长得一样。

新会话第一条消息有发送动效。

### 稳定性

- 无障碍截图以前一直失败，现在能用了
- Shizuku 截图以前会把后台服务搞挂，现在不会
- Termux 通道以前「授权了也用不了」，现在能返回结果
- 崩溃上报收成一个按钮，发到固定地址；发不出去会自动改用邮件
- 代码规范检查修完了存量问题，检查真的会拦了

---

# Muse v2.6.6 (English)

> Card interaction works in places it did not, the proxy no longer half-works, and some screens that used to be empty now have something behind them.

---

## Cards

Buttons inside cards respond now. Several paths were never connected, so controls were drawn but tapping them did nothing.

Two other things: cards no longer show a stray scrollbar, and a card appears while it is being generated instead of waiting for the whole block to finish.

A card can now tell which card it is, read back its bound data, and say where it came from when it sends something back.

**One change you may notice**: "prefer card output" in Settings is now off by default. While it is off, the model will not turn tables, comparisons, or option lists into cards; it writes normally. If you want cards, turn it on in Settings → Chat.

Cards are still being reworked. This is not their final form.

## Proxy

Previously, with a proxy configured, some requests used it and some slipped past; and a change only took effect after a restart.

Now every outbound request follows the proxy, a change applies to the next connection with no restart, and you can name addresses that skip it. "Test connection" on the provider page tells you whether the proxy works.

## Places that used to be empty

- **Experience library, AI understanding cards, group chat memory**: three tabs had implementations but no way in; you can reach them now
- **Quick note attachments**: the indicator never lit up because adding and removing were never connected
- **Desktop widget**: whichever assistant you picked made no difference and the default was always used; it now uses the one you picked
- **"Quick voice input" widget**: tapping it only created a session and never opened the microphone
- **Character relationships**: backups saved them but never restored them; that round trip works now
- **Scheduled task conditions**: conditions you set were not actually applied

## Tool calls

When a tool did not work, the model used to get an error it could not act on.

- It checks the channel before running a tool and says how to enable it when it is off
- Failures say whether it was the network or the arguments, and whether a retry can help
- Failures also carry "attempt N, N left, keep going or stop", so it neither hammers away nor gives up early
- The tool count in the prompt matches reality; it used to claim a number that did not exist
- A tool map is available, so it knows what exists right now

## Memory

You can search past messages across sessions, kept separate per assistant.

A new session used to receive a four-section "nothing yet" block. It no longer does.

When you tell it "don't do that again", that becomes a standing rule rather than applying once.

## Other

- Favorites support multi-select batch actions
- Proactive messages could previously send nothing at all
- Backup missed one data type; character relationships now restore
- Insufficient balance (402) is reported as a balance problem instead of being retried as a network error
- Batch translation honors the glossary and custom style, matching single translation
- Speech error messages are unified; one feature used to have four different wordings
- Group chat duplicated tool arguments, which made a character go silent
- With no channel configured, it no longer writes a log line every 5 seconds
- Session todos persist instead of disappearing when you switch sessions
- The model can read files you pick yourself
- Long pastes can become files; the gallery supports multi-select

## Waiting animation

The waiting indicator is a morphing ink shape (leaf → star → ring), the same everywhere it appears.

New sessions animate the first message.

## Stability

- Accessibility screenshots used to always fail; they work now
- Shizuku screenshots used to take the background service down; they do not anymore
- The Termux channel used to be authorized but unusable; it returns results now
- Crash reporting is a single button sending to a fixed address, falling back to email if it cannot get through
- Existing code-standard violations are cleared, so the checks actually stop new ones
