# Muse v2.6.25

> 这一版一次性放出自 v2.6.22 之后累积的全部改动：界面动效整体翻新、权限体系重做成一份状态、
> 设置信息架构重排，把三轮体验走查里确认的问题逐条修完（含此前准备好但未发布的 v2.6.23 / v2.6.24
> 候选线全部内容），并重做了 Shizuku 与 Termux 两条设备通道的连接方式。

## 新能力

- **动效与按压反馈翻新**：全站动画统一收敛到三档弹簧（跟手 / 有弹性 / 沉稳），导航转场、按压、
  列表位移都走同一套词汇表；按压不再出现圆形涟漪遮罩，改为沿组件轮廓的一圈**边缘凝光**；
  胶囊按钮与图标按钮改为**整面淡色遮罩**，不再做缩小动画；底部面板支持跟手拖拽并按弹簧回弹或滑出关闭；
  开启减弱动画时全部退化为直接切换。
- **共享元素转场试点**：会话列表的头像与标题进入详情页时连续位移，不再是两块独立画面硬切。
- **权限体系重做**：无障碍 / Shizuku / Root / Termux 四条通道统一到一份状态，向导按当前缺口给出
  定向指引（该开哪个、去哪儿开），不再各说各话；Root 通道换用更可靠的授权库，**探测与请求彻底分离**
  ——只查看状态不会再弹出授权框；Termux 版本检测更准，探测结果有缓存、输出被截断时会标注；
  通道状态变化事件驱动刷新，不用手动来回切页；无 Root 设备不再被误报成「配置错误」。
- **设置信息架构重排**：一级分类点进去直接是内容页，不再多一层空壳（聊天、外观、保活等）；
  保活里唯一的二级项提到一级；表情包库设置并入聊天设置页；整组条目都不可达时，
  设置首页不再显示那张点进去为空的分类卡；UI 自动化的各子页改为**整页推入**（各自有页头与返回），
  不再在原页上只换标题和按钮；权限向导「高级通道（Root / Termux）」现在真的可以折叠。
- **会话术语表**：从对话里积累术语候选，达到条件才请你确认，术语页可查看与撤销。
- **人设卡更严格**：导入角色卡走严格校验并给出差分预览，凭据不再随卡片外泄。
- **数据库迁移可见**：迁移步骤、进度与上次结果在应用内可查，不再只有闪屏干等。
- **设置项元数据驱动**：控件类型与高级折叠由清单元数据决定，设置页与网页端不再各写一份。
- **记忆可溯源**：事实条目带证据来源，矛盾条目进入人工裁决回路；画像快照按规则刷新，
  记忆中心显示「依据」行，用户画像页接入自动快照。
- **插件更稳**：按插件运行期熔断（坏插件不再空跑刷屏）、插件页显示熔断状态与剩余秒数、
  市场条目给出版本建议并可切回旧版本。
- **消息渠道**：对话目标策略（白/黑名单）与同人连发去抖合批。
- **主动消息更克制**：智能时机按最近反馈折算发送概率；连续被跳过时退避，并显示静默时长事实行。
- **定时任务能选时间了**：每天 / 每周任务可以选具体时刻，一次性任务可以选日期，
  列表状态不再显示一个假日期。
- **回复语言可关**：正文语言从人设契约里独立成一段，并做成可以关掉的设置。
- **语音回复频率统一到语音播报**：唯一设置处改到「语音播报」里，「它该做与不该做的事」页只留音色入口，
  不再两处各写一份、互相看不出谁生效。
- **聊天里可以选是否显示自己的名字**：新增「显示我的名字」开关，与「显示助手名字」对称。
- **Web Host 运行页与运行诊断卡**：三条只读命令可在网页端查看运行状态。
- **内存更宽**：放开单进程可用堆上限（此前 256MB 对多图与长会话偏紧），
  崩溃日志在 OOM 时会记下当时的内存构成（原生堆、图片缓存等），用于定位真正占堆的一方。

## 重要修复

- **文件夹「添加已有对话」点开即闪退**：弹窗自身已经带滚动，内容里又套了一层滚动，
  内层拿到无限高度约束直接抛异常。移除内层并补上回归测试。
- **卡片里的按钮全部点不动**：卡片改版后，「点一下先聚焦卡片」的透明遮罩把卡片内所有点击都吃掉了。
  现在只在自动高度、内容确实溢出且尚未聚焦时才拦截第一下，聚焦后卡片内交互照常。
- **用户气泡不再像糊了一层**：此前是半透明叠色压在壁纸/渐变上，观感发浑。
  改为主题实色容器 + 同族描边 + 对应前景色，与心情块同一套颜色语言，并随主题走。
- **设置了头像却只显示名字首字**：聊天气泡此前根本没收到头像地址。现在真正加载头像，
  未设置或加载失败才回退首字。
- **Shizuku 一整条链路**：点「授权 Muse」既不弹窗、Shizuku 里也看不到请求的问题修掉了
  ——请求不再因为「要不要展示说明」的判断被提前吞掉，一定会发出去；弹窗没出现时 30 秒超时转人工引导并
  直接打开 Shizuku；点击后立刻提示「请求已发送」，不再毫无反馈；已授权但 Shell 服务未就绪时
  不再显示那个点了也只会拿到「已授权」的按钮，而是给出原因；手动「刷新状态」会清掉绑定失败的退避、
  真正重新绑定服务（此前点了等于没点）；截图改为经管道回 App 再落盘
  （旧实现让 shell 身份去写 App 私有目录，必然失败）。
- **「已授权但 Shell 服务未就绪，点刷新也没用」的根因修掉了**：旧实现在绑定等待超时后会立刻
  **销毁那个其实正在启动的服务进程**，下一次再从零开始 —— 在限制后台进程启动的 ROM 上就形成
  一个永远连不上的循环（真机日志里连续 9 次都卡在同一个超时）。现在超时**不再解绑**，
  连接请求留着，进程随后起来时照样接得上；等待也从「每 50ms 轮询一个字段」换成由连接回调直接唤醒。
  服务进程改为常驻，不再每次执行都付一次冷启动。
- **Shizuku 新增直连快路径**：命令直接在 Shizuku 服务进程里起 `sh -c`，
  **不需要**再冷启动一个独立服务进程、加载 APK 里的代码、等连接回调 —— 只要 Shizuku 本体在跑就能执行，
  状态页因此可以立刻给出「已就绪」而不用等十几秒。只有命令**根本没跑起来**时才退回原来的服务通道；
  命令已经执行过之后绝不退回，避免带副作用的命令被做两遍。
- **Termux 通道「要等一会才返回、有时什么都不返回」**：结果回传从单一广播改成**三路并行**
  （系统广播 / 清单注册的显式广播接收器 / 本机环回连接），任一路先到即用；环回路的上限是 4MB，
  不再受广播约 100KB 的限制，输出被截断时会写清本次走的是哪一路、上限多少。
  另外补了探测结果缓存、服务自检与启动重试，以及**执行证据回填**（对端到底有没有把命令跑起来）。
- **通道失败时助手不再瞎猜**：`device_shell` 与 `termux_exec` 失败会附上「走的哪条路 / 这个失败意味着什么 /
  下一步该怎么办」。关键是说清 `-1` **不是命令的退出码**而是通道级失败 —— 命令可能没执行，
  也可能执行了但结果没回来；不写这句，助手会原样重发同一条命令，有副作用的命令就被做两遍。
  Termux 失败时还会给出通道自检结果与用户侧需要执行的配置命令。
- **权限向导里 Termux 那一栏会说明失败原因**：此前探测结论从 false 变成 false 时界面不刷新，
  失败原因永远看不到。
- **启动时整段插件装配失败**：插件激活桥此前只在启动代码里手工构造、容器里没有定义，
  定时任务解析依赖时直接找不到定义，导致启动期的插件装配整段失败、后台唤醒也被跳过。
  现在两边共用容器里的同一个单例，激活去重状态也不再分叉。
- **设置里的小标题不再贴住上方卡片**：整段被塞进单个列表项的小标题此前借不到列表的行间距，
  会直接贴在上一张卡片上。间距契约写进了组件文档，21 处调用点一并补齐。
- **说明类文字对比度不足**：全站 118 处次要说明文字用的是轮廓色（在深色与部分主题下几乎读不清），
  统一换成对比度达标的次级前景色。
- **没连上模型服务不再"假装在工作"**：此前未配置可用供应商时发消息，会转约一分钟然后什么都不出现。
  现在发送前就告知原因并给出「去设置」，空态也承认内置免费通道的存在；
  工具轮真的没有产出时给出可读原因与重试。
- **通知权限不再占用冷启动第一帧**：改为真正投递通知被系统拦下之后才提出请求，且同一次只提一次。
- **破坏性操作补确认**：删除供应商、删除云端备份版本、WeClaw 解绑、批量删除会话都要先点名确认。
- **未保存草稿不再静默丢失**：媒体设置与渠道编辑离开时若还有未保存内容会先询问；
  渠道 5–14 字段的长表单从 320dp 对话框改为底部面板，键盘弹起后"保存"仍可达。
- **凭据卫生**：引导页、供应商编辑页、代理口令、语音克隆密钥不再写进可被 ROM 落盘的保存状态；
  二维码分享默认**不含** API Key，含密钥需要显式打开并给出警示。
- **已发送的文本不再回到输入框**：上一版的判据不完整，真机强杀证明它认不出主场景；这轮补全并钉住测试。
- **快速记录里设置提醒不再闪退**。
- **任务页三簇"死入口"接通**：归档视图、新建文件夹、多选编辑此前代码都在但没有入口，
  右滑归档后 toast 承诺的"可到「已归档」中恢复"因此落空；现在入口常驻并补齐批量删除确认。
- **设置搜索可直达**：搜到开关类条目不再只把你丢进分类页，而是进组 + 滚动定位 + 短暂高亮。
- **委托不再点击即发送**：拼装好的委托内容先停在输入框，由你决定发送。
- **翻译子菜单的「返回」修好**：此前返回会关掉整个菜单，只能重新长按两轮才能找回位置。
- **状态词不再漏出代码里的枚举名**：状态记号只存语义，人话由渲染层按界面语言说；
  每日总结素材不足时的兜底句、内置挂载名也都跟着界面语言走，不再写死中文。
- **机翻硬伤纠正**：主题名 mono 曾被翻成"传染性单核细胞增多症"、PII 被翻成 IIP、
  暖纸被翻成"温度烫的纸"；专有名词与缩写改为不可翻译或保留原名，并加了按语言逐项断言的回归测试。
- **数量文案改复数**：西语不再出现"Se eliminarán 1 conversaciones"这类单数配复数名词；
  群聊成员计数也纳入同一道守卫。
- **会话标题口径统一**：按当时语言写进数据库的默认标题不再跨语言挂着，
  首页、聊天顶栏、搜索、备份导出对同一条会话显示同一个名字。
- **图标语义重排**：委托不再与群组共用图形、思考/记忆/心情/回复风格不再共用 atom，
  并新增 sticker / expand / reflect 三枚图标；引导语言列表与翻译语言名统一使用语言自称。
- **反馈组件收敛**：15 处系统原生 Toast 统一为站内提示组件；设置页与账户页的文字箭头
  （`>`、`›`）换成图标；思考等级与四处 OAuth/测试错误文案进入多语言资源。
- **TTS 语速/音高不再逐步写库**：改为本地草稿 + 400ms 防抖，合并一次提交，减少写放大与互相覆盖。
- **工作区更好读**：返回与收起两个左箭头分开语义，挂载面板只留一个出口，产物为空时会解释自己
  为什么空；读图回执会说清类型、尺寸、是否随附原图与未随附的原因，没尝试过文字识别时
  不再谎称"没识别到文字"；工具截图改为随图消息 + 人可读文本，
  不再把整串 Base64 当成普通超长输出塞进上下文与日志。
- **助手页一批细节**：简介为空不再截提示词、默认助手不再重复一行、两组分享不再同名、
  温度的"默认"说清是多少、导入入口有可见标签、头像可以点开看大图、标题不再硬写英文；
  Agent 页的头像同时是「换助手」的入口。

## 体验改进

- 引导的语言选项与实际支持的 7 种语言对齐（此前列了一个没有资源的"繁体中文"，选中等于没选），
  系统返回改为回上一步而不是退出应用，中途退出后重进从上次那一步继续，
  完成页四张卡片可以直接点进对应功能，并新增「从备份恢复数据」出口；
  API Key 输入框不再重复字段名，改为说明去哪儿拿。
- 首页回顾条不再与最近会话第一行重复同一条信息；归档入口常驻。
- 停用中的助手在列表里显示「已停用」，不再让人以为它被删了。
- 助手页的行操作菜单换成与全站一致的底部面板；命令面板搜索结果为空标题时不再显示裸 UUID。
- 深色模式的次要文字颜色、描边色与归档实现做了收敛，权限向导的层级与刷新行为对齐。
- 设置首页的整体版式按系统级设计语言重排（标题、分组分隔、行高与留白统一到一套令牌）。
- 小手机在本版**整体收起**：首页与设置里的入口、路由与后台任务都不再露出，
  收起时启动路径会显式取消已排期的后台任务，不留空转；功能本身还在打磨，下个版本重新放出。

## 说明

- 本版包含自 `v2.6.22` 之后累积的全部改动，其中含此前准备好但**未发布**的 `v2.6.23` 与 `v2.6.24`
  两条候选线的全部内容。GitHub 上最近一次公开发行仍是 `v2.6.22`。
- 正式包使用与此前发行相同的签名证书，可直接覆盖升级；分发三包
  （arm64-v8a / armeabi-v7a / universal）外加 `manifest.json`（含各包 SHA-256、数据库版本与来源提交）。
  `x86_64` 仅用于本机模拟器验证，不进入公开分发。
- 已知未修（下一版处理）：首页文件夹行的会话计数可能显示「-1」，那是一个冗余计数发生漂移，
  本轮没有动数据层。
- **待真机确认，本版不宣称已通过**：
  - Shizuku 的直连快路径与新的绑定策略是按真机日志定位后重写的，但**尚未经你的设备验证**。
    快路径以 Shizuku 服务进程的身份执行，其 SELinux 域是否与原服务通道完全等价、
    Sui 后端是否支持这条调用，都还需要实机确认；两者任一不成立时会自动退回原通道，功能不受影响，
    只是回到原来的速度。
  - Termux 此前出现过一次「返回 200 且不给助手任何反馈」，本轮把回传、自检与失败反馈都补齐了，
    但那个 `200` 本身的来源仍未定位（本项目各通道的退出码里没有 200）——若仍复现，需要一份设备日志。
  - 动效手感与低端机降级、TalkBack 全流程、大字号截断、ColorOS 的通知与省电行为，仍是开放项。

---

# Muse v2.6.25 (English)

> This release ships everything accumulated since v2.6.22 in one go: motion is rebuilt across the app,
> the permission system now has a single source of truth, the settings hierarchy is restructured,
> three rounds of UX review findings are closed out, and both device channels (Shizuku and Termux)
> were rewired. It also contains the full content of the previously prepared but never published
> v2.6.23 and v2.6.24 candidates.

## New capabilities

- **Motion and press feedback rebuilt**: all animation now runs through three spring tiers
  (responsive / bouncy / steady) shared by navigation, press and list shifts. Circular ripple masks are
  replaced by an **edge glow** tracing the component outline; capsule and icon buttons use a
  **full-surface tint overlay** and no longer shrink on press; bottom sheets follow the finger and settle
  with a spring or swipe away. Reduced motion degrades everything to instant changes.
- **Shared-element pilot**: a conversation's avatar and title move continuously into the detail page.
- **Permission system rebuilt**: Accessibility / Shizuku / Root / Termux now report one unified state, and
  the wizard gives targeted guidance for the specific gap instead of four channels each telling their own
  story. The Root channel uses a more reliable authorization library and **probing is fully separated from
  requesting**, so merely checking status no longer triggers a prompt. Termux version detection is more
  accurate, probes are cached, truncated output is labelled, channel changes refresh by event rather than
  by re-opening the page, and devices without Root are no longer misreported as misconfigured.
- **Settings hierarchy restructured**: opening a top-level category lands directly on content instead of an
  extra empty shell (chat, appearance, keep-alive and others); the single second-level keep-alive item was
  promoted to the first level; sticker-library settings were merged into the chat page; a category card whose
  entries are all unreachable no longer appears on the settings home; UI-automation sub-pages now push in as
  **full pages** with their own headers and back action instead of swapping the title and buttons in place;
  and the wizard's "Advanced channels (Root / Termux)" section really collapses.
- **Session glossary**: term candidates accumulate from conversations and only ask for confirmation once they
  qualify; the glossary page lists and revokes them.
- **Stricter character cards**: card import validates against a strict schema with a diff preview, and
  credentials no longer travel inside cards.
- **Visible database migration**: steps, progress and the previous outcome are shown in-app.
- **Metadata-driven settings controls**: widget type and advanced grouping come from catalog metadata, so the
  app and the web host no longer maintain two descriptions.
- **Traceable memory**: facts carry evidence, conflicting facts enter a manual resolution loop, profile
  snapshots refresh by rule, the memory center shows an "evidence" row, and the user-profile page uses
  automatic snapshots.
- **More robust plugins**: per-plugin runtime circuit breaking, breaker status with remaining seconds on the
  plugin page, and version suggestions with rollback in the marketplace.
- **Messaging channels**: conversation-target policy (allow/deny lists) plus debouncing and batching of rapid
  messages from the same sender.
- **More restrained proactive messages**: send probability is modulated by recent feedback, repeated skips back
  off, and the quiet-period facts row is shown.
- **Scheduled tasks can pick a time**: daily and weekly tasks choose an hour, one-off tasks choose a date, and
  the list no longer shows a made-up date.
- **Reply language is optional**: the body-language rule became its own contract section and a setting you can
  turn off.
- **Voice-reply frequency unified under voice playback**: the only control now lives in voice playback settings,
  while the "things it should and should not do" page keeps just the voice-tone entry, so two copies no longer
  compete.
- **Chat can show your own name**: a new "Show my name" toggle mirrors "Show assistant name".
- **Web host runtime page and diagnostics card**: three read-only commands expose runtime status.
- **More memory headroom**: the per-process heap ceiling is lifted (256MB was tight for image-heavy and long
  sessions), and out-of-memory crash logs now record the memory composition at the time (native heap, image
  caches and similar) to identify what actually held the heap.

## Important fixes

- **"Add existing conversation" in a folder no longer crashes on tap**: the dialog already scrolled its content,
  and a second nested scroll received an infinite height constraint and threw. The inner scroll is gone, with a
  regression test.
- **Buttons inside cards work again**: after the card rework, the transparent "tap to focus the card" layer
  swallowed every click inside it. It now intercepts the first tap only when the card is auto-sized, actually
  overflowing and not yet focused; interactions work normally once focused.
- **User bubbles no longer look washed-out**: they were a translucent tint stacked over the wallpaper or
  gradient. They now use a solid theme container with a matching border and foreground color, in the same color
  family as the mood block, and follow the theme.
- **A configured avatar no longer falls back to an initial**: the chat bubble never received the avatar URI at
  all. It now loads the avatar and falls back to the initial only when none is set or loading fails.
- **The whole Shizuku path**: tapping "Authorize Muse" produced no dialog and no request visible in Shizuku.
  The request is no longer swallowed by a "should show rationale" check and is always sent; if no dialog appears,
  a 30-second timeout switches to manual guidance and opens Shizuku; tapping immediately confirms the request was
  sent; when authorized but the shell service is not ready, the button that could only ever report "already
  authorized" is replaced by an explanation; manual "Refresh status" clears the bind-failure backoff and really
  rebinds the service (it previously did nothing); and screenshots now stream back through a pipe before being
  written to disk, instead of asking a shell identity to write into the app's private directory, which always
  failed.
- **The root cause of "authorized but the shell service is not ready, and refreshing does nothing" is fixed**:
  on a bind timeout the old code immediately **destroyed the very service process that was still starting up**,
  so the next attempt began from scratch again. On ROMs that restrict background process starts this became a
  self-defeating loop that never connected (a device log showed nine consecutive identical timeouts). Timeouts
  no longer unbind; the pending connection request is kept, so a late callback still lands. Waiting also switched
  from polling a field every 50ms to being woken directly by the connection callback, and the service process is
  now resident instead of paying a cold start on every command.
- **Shizuku gained a direct fast path**: commands start `sh -c` inside the Shizuku server process itself, so
  there is no separate service process to cold-start, no code to load and no connection callback to wait for.
  As long as Shizuku is running, execution works, and the status page can report ready immediately instead of
  after a dozen seconds. It falls back to the previous service channel **only when the command never started**;
  once a command has run it never falls back, so a command with side effects cannot be executed twice.
- **The Termux channel no longer makes you wait, or sometimes returns nothing at all**: result delivery went from
  a single broadcast to **three parallel paths** (system broadcast / a manifest-registered explicit broadcast
  receiver / a local loopback connection), and whichever arrives first wins. The loopback path carries up to 4MB
  instead of the broadcast's roughly 100KB, and truncated output now states which path was used and what its limit
  is. Probe results are cached, the service self-checks and retries its start, and execution evidence is recorded
  (whether the other side actually started the command).
- **The assistant no longer guesses when a channel fails**: `device_shell` and `termux_exec` failures now carry
  "which path was used / what this failure means / what to do next". The key point is that `-1` is **not the
  command's exit code** but a channel-level failure: the command may not have run, or may have run without the
  result coming back. Without that, the assistant resends the same command verbatim and a command with side
  effects gets performed twice. Termux failures also include the channel self-check and the exact configuration
  commands the user needs to run.
- **The Termux row in the permission wizard now states why it failed**: previously a probe result going from false
  to false did not trigger a recomposition, so the reason never appeared.
- **Plugin assembly failed entirely at startup**: the plugin activation bridge was only constructed by hand in
  startup code and had no container definition, so resolving the scheduled-task dependency threw "no definition
  found", taking down the whole startup plugin assembly and skipping background wakeups. Both sides now share one
  container singleton, and activation de-duplication state no longer forks.
- **Settings sub-headings no longer touch the card above them**: a whole section placed inside a single list item
  could not borrow the list's item spacing. The spacing contract is now documented on the component and all 21
  call sites were fixed.
- **Insufficient contrast on explanatory text**: 118 secondary/explanatory text runs across the app used the
  outline color, which is nearly unreadable in dark mode and some themes. They now use a secondary foreground
  color that meets contrast requirements.
- **A missing model service no longer pretends to work**: sending without a usable provider used to spin for
  about a minute and produce nothing. The app now explains why before sending, offers "Open settings", and the
  empty state acknowledges the built-in free channel; when a tool round genuinely yields nothing you get a
  readable reason and a retry.
- **Notification permission is no longer requested on the first frame of a cold start**: it is requested only
  after a notification is actually blocked by the system, at most once per block.
- **Destructive actions now confirm**: deleting a provider, deleting a cloud backup version, unbinding WeClaw and
  bulk-deleting conversations all require naming the target.
- **Unsaved drafts are no longer lost silently**: media settings and channel editing ask before leaving with
  pending edits, and the 5-14 field channel form moved from a 320dp dialog to a bottom sheet so Save stays
  reachable with the keyboard open.
- **Credential hygiene**: onboarding, provider editing, proxy password and voice-cloning keys no longer enter
  saved state that some ROMs persist to disk. QR sharing omits the API key by default; including it is an
  explicit switch with a warning.
- **Sent text no longer returns to the input box**: the previous condition was incomplete and, as a forced kill on
  a real device proved, did not recognize the main scenario. It is now complete and pinned by a test.
- **Setting a reminder from quick capture no longer crashes.**
- **Three dead entries on the task page are wired up**: archived view, new folder and multi-select edit existed in
  code but had no entry point, so the "restore from Archived" promise was unreachable. The entry is now permanent
  and bulk delete asks for confirmation.
- **Settings search goes straight to the control**: a toggle result now opens its group, scrolls to the row and
  briefly highlights it instead of leaving you to hunt.
- **Delegation no longer sends on tap**: the assembled prompt stays in the input box for you to edit.
- **Translate submenu "Back" fixed**: it previously dismissed the whole menu.
- **Status words no longer leak code-level enum names**: tokens store meaning only and the wording is produced per
  interface language at render time; the daily-summary fallback line and built-in mount names follow the interface
  language too instead of being hardcoded.
- **Machine-translation defects corrected**: a theme name was translated as a disease name, an acronym was
  reordered into a different acronym, and "warm paper" became "hot paper". Proper nouns are now untranslatable or
  kept in original form, with per-locale regression assertions.
- **Count strings use plurals**: Spanish no longer renders "1 conversations", and group-member counts are covered
  by the same guard.
- **Conversation titles are consistent**: default titles persisted in an old language no longer stick, and the
  list, chat header, search and backup export show one name for the same conversation.
- **Icon semantics reworked**: delegation no longer shares the group icon, thinking / memory / mood / reply style
  no longer share one glyph, and sticker, expand and reflect icons were added. Onboarding languages and translation
  language names both use endonyms.
- **Feedback components converged**: 15 raw system toasts moved to the in-app surface, text arrows became icons, and
  thinking levels plus four OAuth/test error strings moved into resources.
- **TTS rate and pitch no longer write on every step**: local draft plus a 400ms debounce, committed once.
- **The workspace is easier to read**: the two left arrows for "back" and "collapse" now mean different things, the
  mount panel has a single exit, and an empty artifact view explains why it is empty. Image read receipts state the
  type, dimensions, whether the original was attached and why not, and no longer claim "no text found" when OCR was
  never attempted. Tool screenshots travel as an image message plus human-readable text instead of pushing a whole
  Base64 blob into the context and logs as ordinary long output.
- **A batch of assistant-page details**: an empty introduction no longer truncates the prompt, the default assistant
  is no longer listed twice, two share groups no longer share a name, the "default" temperature states its actual
  value, the import entry has a visible label, the avatar opens full size, the title is no longer hardcoded English,
  and the avatar on the agent page doubles as the entry for switching assistants.

## Experience improvements

- Onboarding languages match the seven actually supported (one listed option had no resources, so selecting it did
  nothing); system Back steps back instead of quitting; re-entering resumes at the step you left; the four completion
  cards are clickable; a "Restore data from a backup" entry was added; and the API key field explains where to get a
  key instead of repeating the field name.
- The home recall strip no longer duplicates the first recent conversation, and the archive entry is permanent.
- Disabled assistants are labelled, so they do not look deleted.
- The assistant row menu uses the same bottom sheet as everywhere else; the command palette no longer shows a bare
  UUID when a title is empty.
- Dark-mode secondary text, border colors and the archive implementation were consolidated, and the permission
  wizard's hierarchy and refresh behavior were aligned.
- The settings home layout was rearranged around the system-level design language (titles, group dividers, row height
  and spacing now come from one set of tokens).
- The mini-phone is **fully withdrawn in this release**: its home and settings entries, routes and background task no
  longer appear, and the startup path explicitly cancels already-scheduled background work when it is withdrawn, so
  nothing idles. The feature is still being reworked and returns in a later release.

## Notes

- This release contains all changes accumulated since `v2.6.22`, including the entire content of the previously
  prepared but **unpublished** `v2.6.23` and `v2.6.24` candidate lines. The most recent public release on GitHub
  is still `v2.6.22`.
- Release APKs are signed with the same certificate as previous versions and can be installed over an existing build.
  Three artifacts are distributed (arm64-v8a / armeabi-v7a / universal) plus `manifest.json` with per-file
  SHA-256, the database version and the source commit. The `x86_64` build is kept for local emulator testing only
  and is not part of the public distribution.
- Known and unfixed (next release): the conversation count on a folder row on the home screen can read "-1" because
  a redundant counter drifted, and this round did not touch the data layer.
- **Pending real-device confirmation — this note does not claim these passed**:
  - The Shizuku fast path and the new binding strategy were rewritten from evidence in a device log but have **not
    yet been verified on your device**. The fast path runs under the Shizuku server process identity, so whether
    its SELinux domain is exactly equivalent to the previous service channel, and whether the Sui backend supports
    this call, both still need checking on hardware. If either does not hold, it falls back to the previous channel
    automatically: functionality is unaffected, only the speed returns to what it was.
  - Termux once returned "200" with no feedback to the assistant. Delivery, self-check and failure feedback are all
    rebuilt in this release, but the origin of that `200` is still unidentified (no channel in this project produces
    an exit code of 200). If it reproduces, a device log is needed.
  - Motion feel and low-end degradation, TalkBack walkthroughs, large-font truncation, and ColorOS
    notification/battery behaviour remain open.
