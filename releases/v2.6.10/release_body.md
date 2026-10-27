# Muse v2.6.10

> 卡片以前只画得出三分之一高度、工具调用会卡在审批上永远不执行；
> 主动消息换了一套记法，记忆能批量删了，设置页按「你来干什么」重排。

---

## 中文

### 卡片只显示一半内容（P0）

自动高度的卡片以前只能露出标题那一截。WebView 返回的尺寸是 CSS 像素，
代码当成物理像素，又除了一次屏幕密度（2.625），每张卡只剩 38% 高度。

现在按 CSS 像素直接用。另外模型可以在 `show_card` 里声明卡片高度，
长卡片不会再被截。

### 工具调用：卡在审批、名字被污染（P0）

三处叠加，只要踩到一处，一次工具调用就白跑：

- **审批超时**：严格模式下每张卡片、每次待办更新都要弹审批，30 秒不点自动拒绝，
  卡片直接消失。卡片和待办都是纯展示，不写文件、不碰设备，
  与「渲染数据」同口径，现在不再需要审批。
- **工具名被正文污染**：模型偶尔把台词和函数名连在一起提交
  （`…等一下.show_card`）。名字对不上会导致整轮失败。现在只在能对上
  本轮暴露的工具名时还原，没有名单不猜。
- **参数是一整句人话**：这种情况以前报「参数格式异常」。现在当成正文交付。

还有一条：已经写出正文的回复，不再为了「声称要调工具却给不出调用」而重试，
不会再把已经写好的内容丢掉。

### 主动消息：从按点轮询换成记账本

旧机制是固定间隔轮询，加一个评分引擎决定要不要开口。漏掉的、重复的、说不该说的，
都出在这个结构上。现在换成记账本：

- **牵挂账本**：助手把「以后想问他的事」记下来，到时机挑一条说，而不是凭空找话
- **结果账**：说了之后有没有被点开、有没有被回复、有没有被划掉，都记下来
- **亲密度**：从这些反馈里学出来，决定今天还能说几次

配套改了几件事：

- 五档量级重排：安静 3 / 克制 6 / 标准 15 / 主动 24 / 黏人不设上限
- 账本面板能看到它最近在惦记什么、今天还能说几次
- 多了「重置」：回到标准档，并把今天的额度一起清零
- 安静时段生效，该睡的时候不会响
- 三张表进备份，换机不丢

### 记忆能批量删了

记忆难免会乱记、重复记，以前只能一条条删，删一条列表就跳回顶部。

现在长按任意一条进入多选，左侧圆点变成选中圈，顶部只剩「取消 / 删除」。
时间轴视图的节点圈同步变成选中圈。删完只刷新一次，不再跳回顶部。

### 过程看得见了

- 输入栏上方一条扁条：此刻在干什么、几个子任务、工具走到哪一步。点开看本轮明细，结束自动消失
- 消息流末尾一张常驻过程卡：在做什么、几步、用了多久。向上翻不会被回收
- 每一步独立可折叠，失败的那步标红并给出结果片段
- 头部统计改成「N 个工具 · N 次思考 · N 次未成功」
- 单个工具也开始折叠成卡，以前要 2 个以上才收
- 后台子任务卡多了「关闭线程」，跑完没收尾的线程可以了结

### 设置页按「你来干什么」重排

一级从 7 组扩到 11 组：助手与人设 / 对话与表达 / 界面与显示 / 模型与推理 /
能力与工具 / 语音与多媒体 / 记忆与知识 / 主动与自动化 / 连接账号与安全 /
存储与空间 / 备份与迁移 / 关于与诊断。

- 工具审批策略归到工具页；楼层限制、经验库归到记忆页；深度思考默认值、压缩模型归到任务路由页
- 每个入口的副标题写清「进去能干什么」
- 表情包从「媒体」页最底部升为独立页
- 常驻后台以前挂在「小手机」设置下面，实际没人找得到，现在有独立入口
- 修了保活服务缺通知渠道导致的启动崩溃

### 渠道能发图了

Telegram / 飞书 / QQ / 钉钉 各自有了出站媒体的实现，统一走一个入口。
以前渠道只能发文字。

- 联系人列表点进去能看整段气泡对话，不再一条消息一行平铺
- 发送失败的错误码显示在列表顶部，不用再翻日志

### 上下文预算重做

以前按窗口固定比例给历史预算，把系统提示、工具定义、输出预留、安全余量当常数。
工具一多，同一条历史在 A 会话超窗、B 会话不超，而固定比例对此毫无感知。

现在这四块按实际占用扣减，留出真实的安全余量。

### 其它

- **跨会话衔接**：新开对话时带回上一条对话的最后几轮（4~40 条可调），默认关闭
- **自定义搜索引擎**：把你自己的检索服务接进 Auto 链最前面
- **DeepSeek 搜索**接进 Auto 链
- **显示助手名字**开关，名字与时间戳解耦
- 头像 28→36dp，气泡底色跟随主题色
- 深色主题下浏览器胶囊、模态遮罩、吸顶搜索栏的脏白和叠影问题
- 「滚到底」按钮按实际底栏高度定位，不再被新的过程扁条压住
- 陪伴天数按助手创建时间算，不再新建会话就归零
- 模型选择弹窗不再只有按钮没有列表
- 备份导入失败、引导页连接失败都改成一句能照做的话，不再抛库表名和状态码
- 产物查看器认 markdown / 表格 / JSON / CSV，导出按类型给扩展名
- 一轮回复中途的工具调用不再重复画头像和名字

### 安装

- 覆盖安装即可，**无需卸载**，数据保留
- 数据库 **111 → 119**，跨了 8 个版本，首次启动自动迁移（含主动消息三张新表）
- 签名与 2.6.1 之后各版相同
- 这一版**关掉了 R8 的优化 pass**，混淆与资源裁剪仍然开启。
  原因是消息气泡是单个 2000+ 行、70+ 参数的 Composable，寄存器用量超过 200 个槽，
  R8 在这个体量上优化会产出类型不一致的 dex，被 Android 14+ 的校验器拒收，
  表现为点开对话直接崩溃

### 已知事项

- 记忆注入与时间提醒不再作为独立段落，职责收进系统提示。记忆的位置和注入时机与以前不同
- 主动等级的额度换了新量级，从旧版本升级后当天剩余次数会按新算法重算一次
- 主动消息现在依赖助手自己记账。如果助手在一段对话里从没记过任何想说的话，
  它就不会主动开口，这是设计如此，不是坏了

---

# Muse v2.6.10 (English)

> Cards used to render at a third of their height and tool calls could stall on an approval prompt forever;
> proactive messages run on a different model now, memory can be deleted in bulk, and Settings is organized by what you came to do.

---

## Cards only showed half their content (P0)

Auto-height cards used to show nothing but the title strip. The WebView reports sizes in CSS pixels,
the code treated them as physical pixels and then divided once more by screen density (2.625),
so every card rendered at 38 percent of its height.

Sizes are now taken as CSS pixels directly. The model can also declare a card height through `show_card`,
so tall cards are no longer clipped.

## Tool calls: stuck on approval, names polluted (P0)

Three separate problems, any one of which wasted a tool call:

- **Approval timeout**: in strict mode every card and every todo update opened an approval prompt.
  Ignore it for 30 seconds and it auto-rejects, so the card vanished. Cards and todos are pure display,
  they write no files and touch no device state, so they now match "render data" and need no approval.
- **Tool name polluted by prose**: the model sometimes submits its line and the function name as one string
  (`…hold on.show_card`). A name that does not match fails the whole round. Names are now recovered only when
  they match a tool exposed in the current round; with no list, it does not guess.
- **Arguments that are one plain sentence**: this used to raise "invalid argument format". It is now delivered as text.

One more: a reply that already produced real text is no longer retried for claiming a tool call it never made,
so written content is not thrown away.

## Proactive messages: from polling to a ledger

The old mechanism was a fixed-interval poll plus a scoring engine deciding whether to speak.
Missed messages, repeats, and saying the wrong thing all came from that shape. It is a ledger now:

- **Pending thoughts**: the assistant writes down what it wants to ask you later, and picks one when the moment fits,
  instead of inventing something to say
- **Outcome ledger**: whether what it said was opened, answered, or dismissed is recorded
- **Intimacy**: learned from those outcomes, and it decides how many times it may speak today

Around that:

- Five levels rebalanced: Quiet 3 / Reserved 6 / Standard 15 / Active 24 / Clingy uncapped
- The ledger panel shows what it is currently holding on to and how many times it may speak today
- A new "Reset" returns to Standard and clears today's quota
- Quiet hours are honored, so it stays silent when you should be asleep
- The three tables are included in backups

## Memory can be deleted in bulk

Memory accumulates wrong and duplicate entries, and deleting one at a time flung the list back to the top.

Long-press any entry to enter selection mode: the dot on the left becomes a check circle and the top bar
keeps only Cancel and Delete. The timeline view switches to check circles too. Deletion refreshes once,
so the list stays where it was.

## You can see what it is doing

- A strip above the input shows what is happening now, how many subtasks, and how far the tools got.
  Tap for this round's detail; it disappears when the round ends
- A persistent process card at the end of the stream shows what it did, in how many steps, and how long it took.
  Scrolling up does not recycle it
- Each step collapses independently; failures are marked red with a snippet of the result
- The header counts by type: N tools · N thoughts · N failures
- A single tool call now folds into a card too; it used to require two
- Background subtask cards gained "close thread", so finished-but-open threads can be dismissed

## Settings organized by what you came to do

The top level went from 7 groups to 11: Assistants and Persona / Chat and Expression / Interface and Display /
Models and Reasoning / Capabilities and Tools / Voice and Media / Memory and Knowledge / Proactive and Automation /
Accounts and Security / Storage / Backup and Migration / About and Diagnostics.

- Tool approval policy moved to the tools page; floor-based context limits and the experience library moved to memory;
  default deep thinking and the compression model moved to task routing
- Every entry's subtitle now says what you will find inside
- Stickers moved from the bottom of the media page to its own page
- Keep-alive used to sit under the mini-phone settings where nobody found it; it has its own entry now
- Fixed a startup crash when the keep-alive service had no notification channel

## Channels can send images

Telegram, Feishu, QQ and DingTalk each have an outbound media implementation behind one entry point.
Channels used to be text only.

- Tapping a contact opens the whole conversation as bubbles instead of one line per message
- A failed send shows its error code at the top of the list instead of staying in the logs

## Context budget reworked

History budget used to be a fixed fraction of the window, treating the system prompt, tool definitions,
output reserve and safety margin as constants. With many tools, the same history overflows in one session
and fits in another, and a fixed fraction cannot tell.

Those four blocks are now deducted by their actual size, leaving a real safety margin.

## Also

- **Cross-session carry-over**: a new conversation can bring the last few turns of the previous one (4 to 40, adjustable), off by default
- **Custom search engines**: plug your own search service in at the front of the Auto chain
- **DeepSeek search** joined the Auto chain
- **Show assistant name** toggle; the name is decoupled from the timestamp
- Avatars 28 to 36dp, bubble color follows the theme
- Dark theme fixes for the browser capsule, modal scrim and pinned search bar
- The jump-to-bottom button is positioned from the real bottom bar height, so the new process strip no longer covers it
- Companion days count from the assistant's creation time, so a new session no longer resets it to zero
- The model picker no longer renders buttons with no list
- Backup import and onboarding connection failures now say what to do next instead of raising table names and status codes
- The artifact viewer recognizes markdown, tables, JSON and CSV, and exports with the right extension
- Tool calls in the middle of a reply no longer redraw the avatar and name

## Installation

- Install over the existing app, **no uninstall needed**, data is kept
- Database **111 to 119**, across 8 versions; the first launch migrates automatically, including the three new proactive tables
- Same signing certificate as every release since 2.6.1
- **R8 optimization passes are disabled in this version**; obfuscation and resource shrinking stay on.
  The message bubble is a single 2000+ line Composable with 70+ parameters and more than 200 registers of pressure;
  R8 optimizing at that size emits dex with inconsistent types, which the Android 14+ verifier rejects,
  surfacing as a crash the moment you open a conversation

## Known issues

- Memory injection and the time reminder are no longer separate blocks; their work moved into the system prompt.
  Where memory lands and when it is injected differs from before
- Proactive levels use new magnitudes, so the remaining count for today is recalculated once after upgrading
- Proactive messages now depend on the assistant keeping its own ledger. If it never wrote down anything it wanted to say
  during a conversation, it will not speak up; that is by design, not a fault