# 插件作者指南

> 面向"想给 Muse 加能力"的人。所有内容与当前代码一致；**尚不可用的能力单独列在最后一节**，
> 不要照文档写完才发现没接上。

## 1. 两种扩展形态，先选对

| | 插件（plugin） | 技能包（SKILL.md） |
|---|---|---|
| 本质 | **会执行的代码** | **纯文本指令** |
| 能做什么 | 调宿主能力、画界面、拦工具调用 | 教模型一套做法（不改代码） |
| 权限 | 需要能力授权（声明 ≠ 授权） | 不需要能力授权 |
| 形态 | `manifest.json` + 入口文件 + 资源 | 一个 `SKILL.md`（可带 `references/`、`assets/`） |

只想"让助手按固定套路办事" → 用技能包。要读写文件、联网、画表单 → 用插件。

## 2. 插件包结构

```
my-plugin/
  manifest.json        必需
  main.js              入口（manifest.entry 指向）
  ui/overview.json     可选：声明式界面（manifest.uiView 指向）
  cards/stock.html     可选：工具卡（manifest.toolCards 指向）
  assets/chart.png     可选：界面里 assetImage 能引用的图片
```

打包成 ZIP 后由用户在「扩展与连接 → 插件」导入；市场安装走同一套三阶段确认。

## 3. manifest 必填与常用字段

```json
{
  "id": "my-plugin",
  "name": "我的插件",
  "version": "1.0.0",
  "entry": "main.js",
  "runtime": "node",
  "capabilities": ["storage", "network"],
  "tools": [
    {
      "name": "lookup",
      "description": "查询并返回结果",
      "functionName": "lookup",
      "parametersJson": "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}"
    }
  ],
  "contributes": { "configuration": [ { "key": "apiBase", "type": "string", "description": "接口地址" } ] },
  "uiView": "ui/overview.json"
}
```

要点：

- `id` 只能用小写字母/数字/`-`/`_`，装上后不可改（改名等于另一个插件）。
- `tools[].functionName` 必须与入口文件里导出的函数名一致；**清单没声明的函数调不到**（执行入口 fail-closed）。
- `capabilities` 是**申请**，不是授权：装完还要用户在权限面板批准（见 §6）。
- 新增字段一律给默认值；参与签名的字段改动必须同步 `tools/market-signer/sign.py`（见 §8）。

## 4. 运行时怎么选

| runtime | 隔离 | 桥接 | 适用 |
|---|---|---|---|
| `node`（**推荐**） | 内置 Node 子进程 + `vm` 无网络上下文，可打断/可杀 | **多轮**：一次调用里可以多次 `muse.bridge()` | 绝大多数插件；**交互面板必须用这个** |
| `js`（默认值，历史兼容） | WebView V8 沙盒 | **单轮**：`return {__bridge__:...}` 就是本次返回值，插件不会恢复执行 | 只做一次桥接就够的老插件 |
| `python` | 同 UID 子进程，**能力总线管不到** | 无桥接 | 需要标准库的重活；必须用户单独批准 `runtime.python`，不接受清单自给 |

换 runtime 不是免费的：`js` 档拿不到多轮桥，所以"先存再读回来"这类流程只能跑在 `node` 上。

## 5. 能力与桥接动作

能力决定**能用哪些桥接动作**。未声明的动作会被拒绝，并给出可行动的提示。

| 能力 | 允许的动作 | 用户看到的人话 |
|---|---|---|
| `resource.read`（别名 `storage.read`） | `fs_list` `fs_read` | 读取文件（只能读到插件自己沙盒目录里的内容） |
| `resource.write`（别名 `storage.write`） | `fs_write` `fs_delete` | 写入文件（只能写进插件自己的沙盒目录） |
| `network` | `http_get` `http_post` | 联网访问外部网址（内网与本机地址会被拦下） |
| `clipboard.read` | `clipboard_read` | 读取剪贴板内容 |
| `clipboard.write` | `clipboard_write` | 向剪贴板写入内容 |
| `notify` | `notify` | 发出通知 |
| `device.info` | `device_info` | 读取设备信息 |
| `storage` | `storage_get` `storage_set` `storage_delete` `storage_list` | 使用插件自己的存储空间（上限 8MB / 单项 256KB） |
| `config.write` | `config_set` | 修改这个插件自己的设置项（密钥类不允许改） |
| `host.tool.invoke` | `tool_invoke` | 请求调用其它工具（每次都会先弹审批） |
| `chat.read` | `chat_read` | 读取当前对话最近内容（隐私，默认关，最多 30 条） |
| `host.model.call` | `model_call` | 用你配置的模型生成内容（计入用量；输入 ≤8KB，输出封顶 4KB） |
| `ui.interactive` | —（门控，不是桥动作） | 显示可提交的表单并运行界面处理函数（**默认关**） |
| `runtime.python` | —（特殊档） | 以 Python 运行代码，能离开沙盒 |

`storage.*` 与 `resource.*` 是**同义拼写**：`storage.read` = `resource.read` = 文件读取。
KV 存储那一条是 `storage`（不带 `.read`），两者不是一回事。

## 6. 权限模型：声明 ≠ 授权

- 安装时按清单播种，标 `install-auto`（可审计）；
- **例外**：`ui.interactive` 与 `runtime.python` 默认不放行 —— 前者会改数据，后者能离开沙盒；
- 升级时**新增**能力会让声明哈希变化 → 需要用户重新批准；只减不加直接放行；
- 用户可随时在「插件 → 能力与权限」里撤销，**下一次调用立即生效**；
- 被拒过的调用会在面板里列出，并提供"本次会话允许"的一次性出口。

## 7. 声明式界面（默认形态）

`manifest.uiView` 指向包内 JSON，由宿主**原生渲染**（跟随用户主题，不占 WebView）。

```json
{
  "type": "column",
  "children": [
    { "type": "heading", "text": "今日概览" },
    { "type": "stat", "label": "完成", "value": "12", "hint": "比昨天多 3" },
    { "type": "progress", "value": 12, "max": 15, "label": "周目标" },
    { "type": "assetImage", "path": "assets/chart.png", "alt": "趋势图" }
  ]
}
```

节点：`column` `row` `divider` `spacer` `heading` `text` `caption` `badge` `stat` `kv`
`list` `progress` `assetImage` `code`，以及交互用的 `form` `input` `submit`。

硬上限（超限**整份拒收**并说明是哪一条）：深度 ≤12、节点 ≤300、单文本 ≤4KB、整份 ≤64KB、输入项 ≤24。

规矩：

- **未知键直接报错**（不是静默忽略）：把 `text` 写成 `tetx` 会得到带位置的报错，而不是一块空白；
- `input.key` 全树唯一，重复即拒（静默覆盖会丢掉用户刚输入的那一格）；
- `input.kind` 只接受 `text` / `number` / `select` / `toggle`；`select` 必须带 `options`；省略即 `text`；
- `assetImage.path` 只能是**包内**相对路径，越界不加载、退回 `alt` 文本。

交互语义（只有"提交-返回"）：

```
用户点提交 → 宿主调用 submit.handler(表单值 JSON) → 插件返回新的组件树 → 宿主整棵替换
```

没有双向绑定。`submit.handler` 必须是清单声明过的函数，且**插件必须用 `runtime: node`**
（js 档单轮桥接做不到"存完再返回新界面"）。提交需要 `ui.interactive` 已获批准。

需要自定义排版/图表时用 `manifest.uiPanel`（HTML，WebView 渲染）作为逃生口。

## 8. 签名与市场

- 签名 payload 是 `manifest.json` 的**规范化 JSON**（紧凑、无空格、字段按声明顺序、含默认值）；
- 默认值不参与序列化的字段（`hooks` `toolCards` `uiView` 等）**必须**在 `sign.py` 的
  `OPTIONAL_MANIFEST_FIELDS` 里，否则 App 验签会因字节不同而失败；
- `sign.py` 的 `MANIFEST_ORDER` 是**有序列表**，位置必须与 App 端字段声明序一致
  （`uiPanel → uiView → toolCards`）。放错位置等于改协议。
- 新增字段若要求完整性保护：四处同步 —— manifest、`SIGNED_TOP_LEVEL_FIELDS`、
  `SignedManifestFieldCoverageTest` 的字段表、`sign.py`。

## 9. 常见拒绝信息对照

| 提示 | 原因 | 怎么改 |
|---|---|---|
| 动作 `fs_write` 未获授权 | 清单没声明 `resource.write`，或用户没批准 | 声明能力后重装/升级，或让用户在面板里开 |
| 插件入口校验失败或工具已移除 | `functionName` 不在清单 `tools` 里 | 补 `tools` 声明，或改函数名 |
| 界面文件无法解析（位置：…） | 键名拼错/类型不符 | 按提示的位置改 |
| 输入 `xxx` 是 select 但没有 options | `select` 缺选项 | 补 `options` |
| 交互面板目前要求 manifest 里写 runtime=node | 用了 js 档 | 改 `"runtime": "node"` |
| 插件需要「Python 运行时」高信任授权 | `runtime: python` 未批准 | 让用户在能力与权限里单独允许 |

## 10. 尚未提供（写完别惊讶）

- `commands`（斜杠命令）、`schedules`（定时激活）、`onMessage`/`onToolCall` 等懒激活事件；
- 决策型 `toolCall.before` hook（可拦/改工具调用）；
- provider 类型插件并入统一插件体系；
- 声明式界面的**交互控件**渲染（`form`/`input`/`submit` 已能解析与门控，
  渲染器接线尚未完成 —— 现在放这些节点会被解析通过，但界面上暂时只显示只读内容）。

以上都在计划内，见 `插件重构_设计.md` 的 P3/P4 与任务板。
