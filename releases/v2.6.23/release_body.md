# Muse v2.6.23

> 这一版把界面动效整体翻新（弹簧化 + 边缘凝光按压），并把两轮体验审查里确认的问题逐条修完：
> 破坏性操作要有确认、没连上模型服务不能装成在工作、密钥不该落进保存状态、机翻硬伤要纠正。

## 新能力

- **动效与按压反馈翻新**：全站动画统一收敛到三档弹簧（跟手 / 有弹性 / 沉稳），导航转场、按压、
  列表位移都走同一套词汇表；按压不再出现圆形涟漪遮罩，改为沿组件轮廓的一圈**边缘凝光**；
  底部面板支持跟手拖拽并按弹簧回弹或滑出关闭；开启减弱动画时全部退化为直接切换。
- **共享元素转场试点**：会话列表的头像与标题进入详情页时连续位移，不再是两块独立画面硬切。
- **会话术语表**：从对话里积累术语候选，达到条件才请你确认，术语页可查看与撤销。
- **人设卡更严格**：导入角色卡走严格校验并给出差分预览，凭据不再随卡片外泄。
- **数据库迁移可见**：迁移步骤、进度与上次结果在应用内可查，不再只有闪屏干等。
- **设置项元数据驱动**：控件类型与高级折叠由清单元数据决定，设置页与网页端不再各写一份。
- **记忆可溯源**：事实条目带证据来源，矛盾条目进入人工裁决回路；画像快照按规则刷新。
- **插件更稳**：按插件运行期熔断（坏插件不再空跑刷屏）、插件页显示熔断状态与剩余秒数、
  市场条目给出版本建议并可切回旧版本。
- **消息渠道**：对话目标策略（白/黑名单）与同人连发去抖合批。
- **主动消息更克制**：智能时机按最近反馈折算发送概率；连续被跳过时退避，并显示静默时长事实行。
- **Web Host 运行页与运行诊断卡**：三条只读命令可在网页端查看运行状态。

## 重要修复

- **没连上模型服务不再"假装在工作"**：此前未配置可用供应商时发消息，会转约一分钟然后什么都不出现。
  现在发送前就告知原因并给出「去设置」，工具轮真的没有产出时也会给出可读原因与重试。
- **通知权限不再占用冷启动第一帧**：改为真正投递通知被系统拦下之后才提出请求，且同一次只提一次。
- **破坏性操作补确认**：删除供应商、删除云端备份版本、WeClaw 解绑、批量删除会话都要先点名确认。
- **未保存草稿不再静默丢失**：媒体设置与渠道编辑离开时若还有未保存内容会先询问；
  渠道 5–14 字段的长表单从 320dp 对话框改为底部面板，键盘弹起后"保存"仍可达。
- **凭据卫生**：引导页、供应商编辑页、代理口令、语音克隆密钥不再写进可被 ROM 落盘的 saved state；
  二维码分享默认**不含** API Key，含密钥需要显式打开并给出警示。
- **任务页三簇"死入口"接通**：归档视图、新建文件夹、多选编辑此前代码都在但没有入口，
  右滑归档后 toast 承诺的"可到「已归档」中恢复"因此落空；现在入口常驻并补齐批量删除确认。
- **设置搜索可直达**：搜到开关类条目不再只把你丢进分类页，而是进组 + 滚动定位 + 短暂高亮。
- **委托不再点击即发送**：拼装好的委托内容先停在输入框，由你决定发送。
- **翻译子菜单的「返回」修好**：此前返回会关掉整个菜单，只能重新长按两轮才能找回位置。
- **机翻硬伤纠正**：主题名 mono 曾被翻成"传染性单核细胞增多症"、PII 被翻成 IIP、
  暖纸被翻成"温度烫的纸"；专有名词与缩写改为不可翻译或保留原名，并加了按语言逐项断言的回归测试。
- **数量文案改复数**：西语不再出现"Se eliminarán 1 conversaciones"这类单数配复数名词。
- **会话标题口径统一**：按当时语言写进数据库的默认标题不再跨语言挂着，
  首页、聊天顶栏、搜索、备份导出对同一条会话显示同一个名字。
- **图标语义重排**：委托不再与群组共用图形、思考/记忆/心情/回复风格不再共用 atom，
  并新增 sticker / expand / reflect 三枚图标；引导语言列表统一使用语言自称。
- **反馈组件收敛**：15 处系统原生 Toast 统一为站内提示组件；设置页与账户页的文字箭头
  （`>`、`›`）换成图标；思考等级与四处 OAuth/测试错误文案进入多语言资源。
- **TTS 语速/音高不再逐步写库**：改为本地草稿 + 400ms 防抖，合并一次提交，减少写放大与互相覆盖。

## 体验改进

- 引导的语言选项与实际支持的 7 种语言对齐（此前列了一个没有繁体资源的"繁体中文"，
  选中等于没选），系统返回改为回上一步而不是退出应用，中途退出后重进从上次那一步继续，
  完成页四张卡片可以直接点进对应功能，并新增「从备份恢复数据」出口。
- 首页回顾条不再与最近会话第一行重复同一条信息。
- 停用中的助手在列表里显示「已停用」，不再让人以为它被删了。
- 助手页的行操作菜单换成与全站一致的底部面板；命令面板搜索结果为空标题时不再显示裸 UUID。

## 说明

- 本版包含自 `v2.6.22` 之后累积的全部改动（64 笔提交）。
- 正式包使用与此前发行相同的签名证书，可直接覆盖升级；分发三包（arm64-v8a / armeabi-v7a / universal），
  x86_64 不进入分发。
- 真机验收仍有开放项：动效手感与低端机降级、TalkBack 全流程、大字号截断、
  ColorOS 的通知与省电行为；本版说明不宣称这些已通过。

---

# Muse v2.6.23 (English)

> This release reworks motion across the app (spring-based transitions and edge-glow press feedback)
> and closes out two rounds of UX review: destructive actions need confirmation, a missing model
> service must not look like "working", secrets must not enter saved state, and machine-translation
> defects have to be fixed.

## New capabilities

- **Motion and press feedback rebuilt**: all animation now runs through three spring tiers
  (responsive / bouncy / steady) shared by navigation, press and list shifts. Circular ripple masks
  are replaced by an **edge glow** that traces the component outline; bottom sheets follow the finger
  and settle with a spring. Reduced motion degrades everything to instant changes.
- **Shared-element pilot**: a conversation's avatar and title move continuously into the detail page.
- **Session glossary**: term candidates accumulate from conversations and only ask for confirmation
  once they qualify; the glossary page lists and revokes them.
- **Stricter character cards**: card import validates against a strict schema with a diff preview,
  and credentials no longer travel inside cards.
- **Visible database migration**: steps, progress and the previous outcome are shown in-app.
- **Metadata-driven settings controls**: widget type and advanced grouping come from catalog metadata,
  so the app and the web host no longer maintain two descriptions.
- **Traceable memory**: facts carry evidence, conflicting facts enter a manual resolution loop,
  and profile snapshots refresh by rule.
- **More robust plugins**: per-plugin runtime circuit breaking, breaker status with remaining seconds
  on the plugin page, and version suggestions with rollback in the marketplace.
- **Messaging channels**: conversation-target policy (allow/deny lists) plus debouncing and batching
  of rapid messages from the same sender.
- **More restrained proactive messages**: send probability is modulated by recent feedback, repeated
  skips back off, and the quiet-period facts row is shown.
- **Web host runtime page and diagnostics card**: three read-only commands expose runtime status.

## Important fixes

- **A missing model service no longer pretends to work.** Previously, sending without a usable provider
  spun for about a minute and then produced nothing. The app now explains why before sending and offers
  "Open settings"; when a tool round genuinely yields nothing, you get a readable reason and a retry.
- **Notification permission is no longer requested on the first frame of a cold start.** It is requested
  only after a notification is actually blocked by the system, and at most once per block.
- **Destructive actions now confirm**: deleting a provider, deleting a cloud backup version,
  unbinding WeClaw, and bulk-deleting conversations all require naming the target.
- **Unsaved drafts are no longer lost silently**: media settings and channel editing ask before leaving
  with pending edits, and the 5–14 field channel form moved from a 320dp dialog to a bottom sheet so
  Save stays reachable with the keyboard open.
- **Credential hygiene**: onboarding, provider editing, proxy password and voice-cloning keys no longer
  enter saved state that some ROMs persist to disk. QR sharing omits the API key by default; including
  it is an explicit switch with a warning.
- **Three dead entries on the task page are wired up**: archived view, new folder and multi-select edit
  existed in code but had no entry point, so the "restore from Archived" promise was unreachable.
  The entry is now permanent and bulk delete asks for confirmation.
- **Settings search goes straight to the control**: a toggle result now opens its group, scrolls to the
  row and briefly highlights it instead of leaving you to hunt.
- **Delegation no longer sends on tap**: the assembled prompt stays in the input box for you to edit.
- **Translate submenu "Back" fixed**: it previously dismissed the whole menu.
- **Machine-translation defects corrected**: theme `mono` was translated as a disease name, `PII` became
  `IIP`, and "warm paper" became "hot paper". Proper nouns are now untranslatable or kept in original
  form, with per-locale regression assertions.
- **Count strings use plurals**: Spanish no longer renders "Se eliminarán 1 conversaciones".
- **Conversation titles are consistent**: default titles persisted in an old language no longer stick, and
  the list, chat header, search and backup export show one name for the same conversation.
- **Icon semantics reworked**: delegation no longer shares the group icon, thinking/memory/mood/style no
  longer share `atom`, and sticker / expand / reflect were added. The onboarding language list uses
  endonyms throughout.
- **Feedback components converged**: 15 raw system toasts moved to the in-app surface, text arrows
  (`>`, `›`) became icons, and thinking levels plus four OAuth/test error strings moved into resources.
- **TTS rate/pitch no longer writes on every step**: local draft plus 400ms debounce, committed once.

## Experience improvements

- Onboarding languages match the seven actually supported (a Traditional Chinese option had no resources,
  so selecting it did nothing); system Back now steps back instead of quitting, re-entering resumes at the
  step you left, the four completion cards are clickable, and a "Restore data from a backup" entry was added.
- The home recall strip no longer duplicates the first recent conversation.
- Disabled assistants are labelled, so they don't look deleted.
- The assistant row menu uses the same bottom sheet as everywhere else; the command palette no longer
  shows a bare UUID when a title is empty.

## Notes

- This release contains all changes accumulated since `v2.6.22` (64 commits).
- Release APKs are signed with the same certificate as previous versions and can be installed over an
  existing build. Three artifacts are distributed (arm64-v8a / armeabi-v7a / universal); x86_64 is not.
- Real-device acceptance remains open: motion feel and low-end degradation, TalkBack walkthroughs,
  large-font truncation, and ColorOS notification/battery behaviour. This note does not claim those passed.
