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
- 不在普通业务页面直接使用 `ModalBottomSheet`；需要底部面板时走 `MuseBottomSheet`，以保持 MuMu 与 Android 手势导航兼容。
- `DropdownMenu` 仅用于紧凑、锚定在触发控件旁边的少量选项；跨供应商/模型/语言的浏览列表必须使用 `MuseSelectionSheet`。
- 多字段表单不得用一串独立弹窗层层套娃；优先改成一个有标题、说明、字段分组和固定底部操作的表单面板。
- 保存按钮必须有明确的禁用态、进行中态和失败反馈；不要点击后直接关闭再静默写入。
- 需要打开页面后自动聚焦的输入框，使用 `MuseTextField(focusRequester = ...)`，不要把 `FocusRequester` 只挂在外层布局。
- 页面级状态用 `rememberSaveable`，临时输入值用 `remember`；列表项内状态必须依赖稳定 key。
- 页面区块使用 `MuseIsland`、`MuseSurface` 或 `SettingsGroup` 之一，避免业务页面各自发明圆角、阴影和间距。
- 新增颜色、圆角、间距和触摸尺寸优先使用主题令牌；交互目标不得小于项目触摸目标令牌。
- 所有弹窗、面板和全屏覆盖层都必须能通过返回键、点击外部或明确关闭按钮结束，不留下不可见遮罩。

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
