# Muse UI 组件库维护规范

这份规范用于统一 Compose 页面、弹窗、表单和状态反馈。新增页面优先复用 `app/src/main/java/io/zer0/muse/ui/common`，不要在业务页面重新实现同类容器。

## 交互选择

| 场景 | 组件 |
| --- | --- |
| 删除、清除、简单确认 | `MuseDialog` |
| 1-4 个字段的短表单 | `MuseFormDialog` |
| 长表单、选择列表、工具参数 | `MuseBottomSheet` |
| 供应商、模型、语言等浏览型选择 | `MuseSelectionSheet` |
| 多步骤编辑、复杂预览、浏览器/媒体 | 独立页面或专用全屏 `Dialog` |
| 页面顶部返回与标题 | `MuseTopBar` |
| 设置分组与开关行 | `SettingsGroup`、`SettingsItemRow`、`SettingsSwitchRow` |
| 文本输入 | `MuseTextField` |
| 短暂成功/失败反馈 | `MuseToast` 或 `MuseSnackbar` |
| 空态、错误、加载 | `MuseEmptyState`、`MuseErrorStateBox`、`MuseLoadingState` |
| 图标操作 | Material/Tabler 图标 + `IconButton`，必须有 `contentDescription` |

## 约束

- 不新增 Material3 原生 `AlertDialog`，确认与短表单统一走 `MuseDialog` / `MuseFormDialog`。
- 不在普通业务页面直接使用 `ModalBottomSheet`；底部面板走 `MuseBottomSheet`。其受控 Dialog 外壳统一测量 Insets，单独绘制避开导航栏的遮罩，业务页面不要再次计算窗口位置。
- `DropdownMenu` 仅用于紧凑、锚定在触发控件旁边的少量选项；跨供应商/模型/语言的浏览列表必须使用 `MuseSelectionSheet`。
- 多字段表单不得用一串独立弹窗层层套娃；优先改成一个有标题、说明、字段分组和固定底部操作的表单面板。
- 保存按钮必须有明确的禁用态、进行中态和失败反馈；不要点击后直接关闭再静默写入。
- 需要打开页面后自动聚焦的输入框，使用 `MuseTextField(focusRequester = ...)`，不要把 `FocusRequester` 只挂在外层布局。
- 页面级状态用 `rememberSaveable`，临时输入值用 `remember`；列表项内状态必须依赖稳定 key。
- 页面区块使用 `MuseIsland`、`MuseSurface` 或 `SettingsGroup` 之一，避免业务页面各自发明圆角、阴影和间距。
- 新增颜色、圆角、间距和触摸尺寸优先使用主题令牌；交互目标不得小于项目触摸目标令牌。
- 所有弹窗、面板和全屏覆盖层都必须能通过返回键、点击外部或明确关闭按钮结束，不留下不可见遮罩。
- 确认弹窗最大宽 320dp，窄屏两侧至少 24dp；按钮视觉紧凑但点击区域至少 48dp。短操作取消在左、确认在右，大字体回退纵排。
- 模态遮罩必须先排除 `navigationBars` 再绘制；独立 Dialog 禁止平台 dim 和导航栏保护色二次叠加，系统栏图标对比度沿用宿主。
- `CardGroup` 分组标题对齐卡片内侧 16dp，不用假定图标宽度的 56dp 缩进；`SectionLabel` 与 `CardGroup` 标题保留前一模块后的 20dp 留白。
- 可进入详情的管理行整行可点，右侧有「更多」时不再并列放右箭头；状态/检测结果放标题下方，可换行的辅助区不得挤压标题。
- 多图网格使用统一正方形缩略图并固定 6dp 间距，点开预览看完整原图；单图继续完整比例显示。

## 迁移顺序

1. 先替换原生 `AlertDialog` 和裸 `ModalBottomSheet`。
2. 再合并同一页面中重复的新增/编辑/重命名弹窗。
3. 最后处理视觉细节：标题层级、按钮顺序、键盘避让、动画和空态。

## 当前基线

- 原生 `AlertDialog`：0 处。
- 统一短表单组件：`MuseFormDialog`。
- 统一选择面板：`MuseSelectionSheet`，已用于图片模型、视频模型、语音、下拉选择等 5 个入口。
- 快速记录设置：使用 `MuseBottomSheet`。
- 长按菜单：单聊与群聊使用 `MusePopover`，优先按压点定位，空间不足时自动翻转。
- 保留独立 `Dialog`：命令面板、全屏媒体、浏览器查看器、桌面右键菜单、委派修改确认等明确窗口场景。
- 当前基线：业务 `ModalBottomSheet` 调用 0 处；业务 raw `OutlinedTextField` 0 处；统一输入框支持内部焦点请求。

## 水平内边距（内容不得贴边）

左侧文字顶到屏幕边缘，几乎只有两个原因：容器的水平内边距被显式压成 0，或者同级元素漏了内边距。这两类都算缺陷。

### 规则

| 层级 | 左边距 | 取值方式 |
| --- | --- | --- |
| 页面正文 / 卡片 / 列表 item | 16dp | `MusePaddings.screen` |
| 底部面板（`MuseBottomSheet`）内容 | 16dp | 默认值，调用方不要再传 `horizontalPadding` |
| 设置分组（`SettingsGroup` / `SettingsItemRow`） | 组件内部已含 | 页面不要再包一层水平 padding |
| 顶栏标题 / 返回 | 16dp | 由 `MuseTopBar` 内置 |
| 浮动菜单（`MuseFloatingActionMenu`） | 面板内 8dp | 组件内部已含 |
| 行内图标与文字 | 10-12dp | `MusePaddings.auxGap` |

底线：同一屏里所有正文类文字的左边线必须对齐到同一条线，允许的偏差只有「面板内额外 8dp」这一种。

### 检查清单

- [ ] 同一区块的标题与副标题用同一个左边距。最常见的漏项：标题在外层 `Row` 上带了 `padding(horizontal = screen)`，而副标题是它的**兄弟节点**，漏了自己的边距。
- [ ] 底部面板不要传 `horizontalPadding = 0.dp`。
- [ ] 面板内容不要再叠一层 `padding(horizontal = ...)`，否则会出现双倍缩进（16 + 20 = 36dp）。
- [ ] 长按菜单、加号菜单这类「一行一张圆角卡片」的列表，卡片两侧必须留边距，不允许卡片贴着屏幕边。
- [ ] 横向滚动画廊、全屏媒体这类确实需要贴边的场景，在代码里写明原因。

### 自动护栏

`ci/script/check_horizontal_inset.py` 拦截显式清零：`horizontalPadding = 0.dp`、`padding(horizontal = 0.dp)`、`PaddingValues(horizontal = 0.dp)`、`contentPadding = 0.dp`。确需贴边的行尾加 `// inset-guard: allow` 并说明原因，否则 CI 直接失败。

## 流体动效与凝光令牌（2026-11 新增）

### 弹簧档位（唯一动效词汇表）

- 三档物理参数定义在 `ui/theme/DesignTokens.kt`（`MuseSpringTier` + 六常量），web 侧 `tokens.json` 的 `motion.springs` 与之由 `DesignTokenParityTest` 双向锁定；改一处必须同步另一处。
- 选用规则：跟手（按压缩放、小状态翻转、内容块位移）用 `RESPONSIVE`；明显过冲的弹出回弹用 `BOUNCY`；导航页位移、尺寸展开这类"终点必须停稳"用 `STEADY`。
- 透明度分量一律继续走 tween（`ENTER_MS/EXIT_MS` 等时长令牌不变）——弹簧会让 alpha 越过 [0,1]，时长令牌仍有消费方。
- 降级契约集中在 `MuseMotion.spring()/springSpec()`：系统动画缩放 ≤0 时统一 snap。业务组件不得再自带 `if (reducedMotion) snap() else spring(...)` 的局部特判。

### 边缘凝光

- 按压反馈只有一种合法形态：沿组件轮廓的双层描边 + 触点方向偏置，**任何圆形扩散遮罩都不允许**（含自定义 ripple 变体）。
- 全局入口是 `Theme.kt` 的 `LocalIndication provides edgeGlowIndication()`；组件需要自己的轮廓时包 `MuseGlowShapeProvider(shape)`，不要自绘按压层。
- 强度参数在 `MuseGlowTokens`（浅/深两套 peakAlpha 已入 `tokens.json` `colors.alpha.edgeGlow*` 并锁 parity）。
- 保持 `indication = null` 的白名单只有：消息气泡（性能边界）与自绘反馈的 `MuseCardPress`（缩放+色彩已构成完整反馈，不叠加）。

### 共享元素

- 全应用只允许 `MainActivity` 一处 `MuseFluidSharedHost`（内含唯一 `SharedTransitionLayout`）；多 scope 并存会让 key 互不相认。
- 业务侧只写 `Modifier.museSharedElement(key = ...)`，key 用稳定业务 id（如 `assistant-avatar-<id>`、`session-title-<id>`），两端必须一致。
- 跨窗口弹层（Dialog 类）不能参与共享元素；需要连贯形变的查看器必须先改成树内浮层，不要试图跨窗口接线。

### 越界与玻璃高光

- 越界回弹交回平台默认效果（API 31+ 橡皮筋、更低版本硬停自然降级），不再全局禁用；如某滚动容器确需关闭，写行内注释说明原因。
- 玻璃高光参数唯一来源 `MuseGlassLight`；真玻璃与假玻璃消费点不得各自维护同义常数。
