# Muse v2.6.10

> 卡片以前只画得出三分之一高度、工具调用会卡在审批上永远不执行；
> 主动消息换了一套记法，记忆能批量删了，设置页按「你来干什么」重排。

---

## 中文

### 卡片只显示一半内容（P0）

自动高度的卡片以前只能露出标题那一截。WebView 返回的尺寸是 CSS 像素，
代码当成物理像素，又除了一次屏幕密度（2.625），每张卡只剩 38% 高度。

现在按 CSS 像素直接用。另外模型可以在 `show_card` 里声明卡片高度
（`auto`、固定 dp、`16:9`），多行布局自己声明，不让宿主猜，长卡片不会再被截。

**一个你会马上感知到的变化**：「偏好卡片输出」重新默认打开了
（2.6.6 曾把它关掉）。升级后助手会更主动地把表格、对比、选项做成卡片。
不想要就去 设置 → 聊天 关。

卡片生成用的提示词这版也换了一套设计词汇表：外壳、指标、表格、徒标这些现成版式，
一套数据色阶，一个图表入口，还有声明式回传。以前模型是在一屏里手写样式源码，
现在是有版式可套。产物也多了 `table` 类型，表格不再被塞进文档里变成一堆竖线。

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

### 渠道：能发图了，也修了一堆

Telegram / 飞书 / QQ / 钉钉 各自有了出站媒体的实现，统一走一个入口。
以前渠道只能发文字。

收侧的问题更隐蔽：钉钉、飞书、Telegram 的图片消息以前只产出一个字面量「[图片]」，
图片根本没下载下来，识图那条链路等于完全失效。现在三家都真的下载了，失败会降级成占位并记日志。

QQ 还踩了一串：

- **断线续传**：持久化会话 id 和最后一个序号，重连带回断档事件。以前断线就开新会话，中间的消息直接丢
- **被动回复窗口分开算**：官方是群聊 5 分钟、单聊 60 分钟，次数 5 / 4。以前两边都按 60 分钟，
  群聊会拿失效的消息 id 去发，单聊又会把本来能回一小时的对话题早判过期
- **过期返回空串**：这个空串刚好绕开了缓存里可用的消息 id，改回 null
- **群消息不重复**：@ 机器人会命中两个事件，同一条只处理一次；没 @ 的只留痕不回
- **没拿到 5 分钟内的消息 id**：当场就说清楚，不再硬发然后被抛成「它不回我」

另外联系人列表点进去能看整段气泡对话，不再一条消息一行平铺；
发送失败的错误码显示在列表顶部，不用再翻日志。

### 上下文预算重做

以前按窗口固定比例给历史预算，把系统提示、工具定义、输出预留、安全余量当常数。
工具一多，同一条历史在 A 会话超窗、B 会话不超，而固定比例对此毫无感知。

现在这四块按实际占用扣减，留出真实的安全余量：8000 的窗口不再给历史 4800，
而是 2880，因为模型得先把这条回复写完。超限判断也只看历史部分，
不再把系统提示、检索结果算进可压缩预算。

压缩本身修了两处积病：摘要现在附带「这一段改过哪些文件」，从写类工具调用里抽，
不靠模型转述；上一版摘要会哺回模型要求合并，不再每次压缩把早期内容淡一层。

### 工具回传结果那一轮必然失败（P0）

Responses API 里工具结果字段写成了 `content`，服务端要的是 `output`，
直接 422 `missing field 'output'`。表现是工具执行完、把结果回传的那一轮必定失败，
前面做得再对也白搭。已改。

### 助手开始记事了

- **`note_thought`**：把「以后要问你的事」写进牵挂账本，是主动消息的燃料
- **`remember_preference`**：把「以后别这样」沉淀成硬约束，注入到提示词里
- **`tool_status`**：一次报清「已注册的」和「现在真能用的」，按缺哪条通道分组。
  没就绪的工具会明确说先别答应你
- **通道预检**：注定失败的工具在调用前就拦下，并给出开通路径，不再先答应再撞墙
- **`read_user_file`**：经系统选择器读你亲手选的文件，非文本类型直接说明为什么不行
- **`workspace_grep`**：在工作区里按文本或正则递归搜，返回路径、行号、内容，命中 100 条就停并标明截断
- **`memory_config_get` / `memory_config_set`**：记忆参数以前只能在设置页手拖，现在模型也能读写

### 主动消息：宁可不发

加了几条硬约束：距上次 3 小时以内不发；你刚说过话 10 分钟内不发；
正在生成不发；额度不足不发。能不能接上话交给模型判断，接不上就 skip，
不再用评分硬凑一条出来。

### 其它

- **跨会话衔接**：新开对话时带回上一条对话的最后几轮（4~40 条可调），默认关闭
- **自定义搜索引擎**：把你自己的检索服务接进 Auto 链最前面
- **DeepSeek 搜索**接进 Auto 链
- **显示助手名字**开关，默认开，与时间戳解耦
- **会话待办真正落库**，切会话、重启都不丢；主列表还会提示「还有 N 个会话留着没做完的事」
- 渠道接收器以前会「永久死亡」：停用再启用就再也收不到消息，现在改为延时重试
- 卡片状态 JSON 以前只在一处剥离，复制、分享、导出、朗读、搜索索引都会看到那截尾巴，现在收敛成三个出口
- 备份漏了字符串集合类设置（朋友圈收藏、相册收藏），换机会丢且没有任何提示，现在补上
- 会话滚动位置落盘，杀后台回来还在原处
- 压缩阈值统一到 20/15（长记忆 10/8），改一处不再有路径不生效
- 头像 28→36dp，气泡底色跟随主题色
- 深色主题下浏览器胶囊、模态遮罩、吸顶搜索栏的脏白和叠影问题
- 「滚到底」按钮按实际底栏高度定位，不再被新的过程扁条压住
- 陪伴天数按助手创建时间算，不再新建会话就归零
- 模型选择弹窗不再只有按钮没有列表
- 备份导入失败、引导页连接失败都改成一句能照做的话，不再抛库表名和状态码
- 产物查看器认 markdown / 表格 / JSON / CSV，导出按类型给扩展名
- 一轮回复中途的工具调用不再重复画头像和名字
- 小手机私信不再对谁都一样粘人，按关系亲密度和意愿档位算，并遵守每日上限与免打扰时段
- 预置供应商补了模型：Agnes 2.0→2.5（上下文 131072→262144），几个中转站不再是空列表
- 内置模型清单翻新到 10 月 26 日，多了几个新模型家族；文件从 137KB 长到 184KB
- 内置 Node 运行时从 24 升到 26（npm 11.21.1），MCP 服务、技能脚本、终端都跑在它上面
- 诊断包多带完整文件日志（1MB 滚动）。以前只有内存里最近 1000 条，渠道类问题往往在被冲掉之后才需要看
- 修了「自己导出的备份导不回去」：对账时把内建文档排除了，新设备上算出来是 0，数量对不上
- 修了「恢复失败后每次开机都重来一遍」：回滚本身也失败时把账本了结，副本留给诊断
- 修了委派清单把自己列进去的问题；修了「用户档案里写的名字」与身份锚点打架、
  改一个就出现两个名字的问题
- 群聊主持人留空会让模板失效，现在兼容；建群后不再发现「我设的主持人没生效」
- Termux 命令成功却报「错误码 -1」：成功码本来就是 -1，已改正
- 图像和视频模型不再被品牌默认值当成 131072 上下文，免得把它们排进长上下文队列

### 安装

- 覆盖安装即可，**无需卸载**，数据保留
- 数据库 **111 → 119**，跨了 8 个版本，首次启动自动迁移（含主动消息三张新表）
- 签名与 2.6.1 之后各版相同
- 这一版**关掉了 R8 的优化 pass**，混淆与资源裁剪仍然开启。
  原因是消息气泡是单个 2000+ 行、70+ 参数的 Composable，寄存器用量超过 200 个槽，
  R8 在这个体量上优化会产出类型不一致的 dex，被 Android 14+ 的校验器拒收，
  表现为点开对话直接崩溃。代价是包体会比上一版大一些

### 已知事项

- 记忆注入与时间提醒不再作为独立段落，职责收进系统提示。记忆的位置和注入时机与以前不同
- 主动消息的旧参数（间隔、发送概率、每日上限）在新模型里没有对应物，不再起作用，
  节奏改由牵挂账本和主动等级决定。升级后账本是空的，会先安静几天，这是设计意图
- 工具默认可见范围变了：自检、记忆、卡片、读文件，以及状态自查、通知、任务组织、委派入口，
  从「可选」提到「标准」，助手不靠 `find_tools` 也能直接看到
- 工具描述这一版整批改成了中文，模型读到的说明语言变了。英文界面下这些描述不跟着变
- 系统提示新增了「效力顺序」：当轮指令 > 本会话模式要求 > 人格设定 > 记忆、世界书、知识库。
  角色口吻不再压过你这一轮的具体要求
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

Sizes are now taken as CSS pixels directly. The model can also declare a card height through `show_card`
(`auto`, a fixed dp, or `16:9`), so multi-line layouts state their own height instead of letting the host guess,
and tall cards are no longer clipped.

**One change you will notice right away**: "prefer card output" is on by default again
(2.6.6 turned it off). After upgrading, the assistant will more readily turn tables, comparisons and option lists
into cards. Turn it off in Settings → Chat if you do not want that.

The card generation prompt now uses a design vocabulary: ready-made shells, metric rows, tables and badges,
a data color scale, a chart entry point and declarative callbacks. The model used to hand-write a screen of style code;
now it has layouts to fill. Artifacts gained a `table` type too, so tables are no longer crammed into a document
and rendered as a wall of pipes.

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

## Channels can send images, and a lot else got fixed

Telegram, Feishu, QQ and DingTalk each have an outbound media implementation behind one entry point.
Channels used to be text only.

The inbound side was worse than it looked: images arriving through DingTalk, Feishu and Telegram used to produce
the literal text "[image]" and were never downloaded, so vision was effectively dead on those channels.
All three download now, and fall back to a placeholder with a log line when they fail.

QQ had a run of its own:

- **Reconnect resume**: the session id and last sequence number are persisted and a reconnect replays the gap.
  A drop used to open a new session, losing everything in between
- **Separate reply windows**: the platform allows 5 minutes for groups and 60 for direct messages, with 5 and 4 replies.
  Both used to use 60, so groups sent with an expired message id and direct chats gave up early
- **Expired lookups returned an empty string**, which happened to bypass the usable message id in the cache; it returns null now
- **Group messages are no longer handled twice**: mentioning the bot matched two events, and a message without a mention
  is now recorded without a reply
- **When no message id within 5 minutes is available**, it says so instead of sending anyway and being read as silence

Tapping a contact also opens the whole conversation as bubbles instead of one line per message,
and a failed send shows its error code at the top of the list instead of staying in the logs.

## Context budget reworked

History budget used to be a fixed fraction of the window, treating the system prompt, tool definitions,
output reserve and safety margin as constants. With many tools, the same history overflows in one session
and fits in another, and a fixed fraction cannot tell.

Those four blocks are now deducted by their actual size, leaving a real safety margin: an 8000 window no longer
hands 4800 to history but 2880, because the model has to finish its reply first. The overflow check looks only at
history and no longer counts the system prompt and retrieval results against the compressible budget.

Compression itself had two long-standing problems: the summary now lists which files the compressed span touched,
taken from write-type tool calls rather than the model retelling it; and the previous summary is fed back
for merging, so early content no longer fades a little with every compression.

## The round that sends tool results back always failed (P0)

The Responses API request named the tool result field `content`; the server wants `output`
and answers 422 `missing field 'output'`. So the round right after a tool ran always failed,
no matter how well everything before it went. Fixed.

## The assistant keeps notes now

- **`note_thought`** writes down what it wants to ask you later; this is what feeds proactive messages
- **`remember_preference`** turns "don't do that again" into a hard constraint injected into the prompt
- **`tool_status`** reports what is registered and what actually works right now, grouped by the channel that is missing.
  Tools that are not ready say so up front instead of promising and failing
- **Channel preflight** stops a tool that cannot work before it runs, and says how to enable it,
  instead of agreeing first and hitting the wall
- **`read_user_file`** reads files you pick yourself through the system picker, and explains why it cannot read non-text types
- **`workspace_grep`** searches the workspace recursively by text or regex, returning path, line and content,
  stopping at 100 hits and marking the result as truncated
- **`memory_config_get` / `memory_config_set`** let the model read and write memory parameters that only a slider could change before

## Proactive messages would rather say nothing

Hard limits were added: nothing within 3 hours of the last message, nothing within 10 minutes of you speaking,
nothing while a generation is running, nothing when the quota is spent. Whether it can pick up the conversation
is left to the model, which can skip. No more forcing a message out of a score.

## Also

- **Cross-session carry-over**: a new conversation can bring the last few turns of the previous one (4 to 40, adjustable), off by default
- **Custom search engines**: plug your own search service in at the front of the Auto chain
- **DeepSeek search** joined the Auto chain
- **Show assistant name** toggle, on by default, decoupled from the timestamp
- **Session todos are persisted** now; switching sessions or restarting keeps them, and the main list notes
  how many sessions still have unfinished items
- Channel receivers used to die permanently: disabling and re-enabling a channel meant no messages ever again.
  They retry now
- Card state JSON used to be stripped in one place only, so copying, sharing, exporting, reading aloud
  and the search index all showed that trailing fragment. It is now stripped at three clear exits
- Backups missed string-set settings (moments favorites, gallery favorites), which were lost on a new device
  with no warning. Now included
- Session scroll position is persisted, so it survives the process being killed
- Compression thresholds unified at 20/15 (long memory 10/8), so changing one place actually applies everywhere
- Avatars 28 to 36dp, bubble color follows the theme
- Dark theme fixes for the browser capsule, modal scrim and pinned search bar
- The jump-to-bottom button is positioned from the real bottom bar height, so the new process strip no longer covers it
- Companion days count from the assistant's creation time, so a new session no longer resets it to zero
- The model picker no longer renders buttons with no list
- Backup import and onboarding connection failures now say what to do next instead of raising table names and status codes
- The artifact viewer recognizes markdown, tables, JSON and CSV, and exports with the right extension
- Tool calls in the middle of a reply no longer redraw the avatar and name
- Mini-phone direct messages are no longer equally clingy toward everyone; they follow intimacy and willingness,
  the daily cap and the same do-not-disturb window as the main chat
- Preset providers gained models: Agnes 2.0 to 2.5 (context 131072 to 262144), and several relay providers are no longer empty lists
- The built-in model catalog was refreshed through October 26 and gained several new model families; the file grew from 137KB to 184KB
- The bundled Node runtime moved from 24 to 26 (npm 11.21.1); MCP servers, skill scripts and the terminal all run on it
- Diagnostic bundles now include the full file log (1MB rolling). It used to carry only the last 1000 lines in memory,
  and channel problems are usually wanted after those are gone
- Fixed "my own export will not import": the self-check excluded built-in documents, so a new device counted 0 and the totals disagreed
- Fixed "a failed restore retries on every boot": when the rollback itself fails the ledger is settled and the copy kept for diagnostics
- The delegation list no longer includes the assistant itself, and the name written in your profile no longer fights
  the identity anchor and produces two different names
- An empty group chat host no longer silently disables the template you picked
- A Termux command that succeeded no longer reports "error code -1"; the success code is -1
- Image and video models are no longer treated as 131072-context because of a brand default

## Installation

- Install over the existing app, **no uninstall needed**, data is kept
- Database **111 to 119**, across 8 versions; the first launch migrates automatically, including the three new proactive tables
- Same signing certificate as every release since 2.6.1
- **R8 optimization passes are disabled in this version**; obfuscation and resource shrinking stay on.
  The message bubble is a single 2000+ line Composable with 70+ parameters and more than 200 registers of pressure;
  R8 optimizing at that size emits dex with inconsistent types, which the Android 14+ verifier rejects,
  surfacing as a crash the moment you open a conversation. The APK is somewhat larger than the previous version as a result

## Known issues

- Memory injection and the time reminder are no longer separate blocks; their work moved into the system prompt.
  Where memory lands and when it is injected differs from before
- The old proactive settings (interval, send probability, daily cap) have no counterpart in the new model and no longer apply;
  pacing comes from the ledger and the proactive level. After upgrading the ledger is empty, so it stays quiet
  for the first few days. That is intended
- Default tool visibility changed: self-check, memory, cards and file reading, plus status check, notifications,
  task organization and delegation, moved from optional to standard, so the assistant sees them without `find_tools`
- Tool descriptions were translated to Chinese in one sweep, so the language the model reads them in changed.
  These descriptions do not follow the English interface
- The system prompt gained a precedence section: this turn's instruction > this session's mode > persona >
  memory, world book, knowledge base. A character voice no longer overrides what you asked for right now
- Proactive levels use new magnitudes, so the remaining count for today is recalculated once after upgrading
- Proactive messages now depend on the assistant keeping its own ledger. If it never wrote down anything it wanted to say
  during a conversation, it will not speak up; that is by design, not a fault