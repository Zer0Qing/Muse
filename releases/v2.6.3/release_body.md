# Muse v2.6.3

> 修掉 v2.6.2 的必崩回归，并集中处理 GitHub 上 #3 ~ #7 的反馈。

---

## 中文

### 紧急修复：设置 → 聊天 必崩

v2.6.2 打开「设置 → 聊天」会崩溃（往下滚动到表情包区域时触发）：

```
java.lang.IllegalFormatConversionException: d != java.lang.String
```

原因是那句提示文案在组合期用了带参的 `stringResource(id, "%1$d")`，
它会立即执行 `String.format`，把字符串塞进整数槽位。已改为无参 `stringResource`。
顺带修了「转发成功」提示永远显示占位符的同源问题。

v2.6.1 及更早版本没有此问题。

### WebServer 无法启动（#4）

`WebServer 启动失败: Array has more than one element` 是 Ktor 3.0.x 在 R8 混淆后的
release 构建里的已知缺陷（KTOR-7298），且只在 release 包复现，debug 测不出来。

- Ktor 3.0.3 → 3.1.3（官方修复）
- 补了 ServiceLoader / engine factory 的 R8 keep 规则作为双保险

### 工具调用静默失败（#6）

**#6**：群聊里 `channel_reply` 校验失败（如 content 为空）时，返回值被直接丢弃，
流程一路走到 `implicit pass`，用户看到的是「整轮无回复、也没报错」。现在：

- 工具执行失败会把错误回填给模型，让它修正后重试（有次数上限）
- 空响应不再等同于「模型主动跳过」，会先重试一次才降级
- 显式 `[PASS]` 与空响应彻底分开，日志能区分

> **#5 说明**：DeepSeek Flash 等模型「声称调工具但没返回有效 `tool_call`」时，
> reasoning 内容仍会被兜底成正文显示。这是既有产品决定（「吐可以，空不行」），
> 与「不要把思考当正文」存在语义冲突，需要单独评估后再改。本版**未改动**这一点，
> 避免在紧急版本里带入未定论的行为变更。

### 中转站多模态模型被误判（#3.1）

内置模型能力表会无条件覆盖上游声明，导致中转站上确实支持识图的同名模型
（如 `deepseek-v4.1-flash`）被判为纯文本，识图完全不可用。
现在若上游明确声明支持 `image`，不再被内置标签覆盖。

也可以在「供应商 → 模型 → 编辑模型能力」里手动修正，改过之后以你的设置为准。

### 供应商「测试」按钮误报（#3.2）

多数模型点测试都提示失败，但实际聊天正常。三个原因都修了：

- `maxTokens` 从 16 提到 256 —— 原值对思维链模型不够，额度全被 reasoning 吃掉
- 有工具调用或只有思考链输出，现在都算链路通
- 失败时给出可操作原因（Key 无效 / 模型不存在 / 限流 / 超时 / 上游格式异常），不再只丢一句上游原文

### 报错信息截断且无法复制（#3.3）

错误详情不再被截断到 80 字，保留完整原文。

### Termux 授权无反馈（#3.4）

- 「去授予权限」跳转后仍给出逐步指引（应用信息首屏看不到该开关）
- 配置提示改为完整两步命令：写入配置 **+** `termux-reload-settings`（只重启 Termux 可能不生效）
- 新增「复制命令」按钮，避免手敲出错

### 回复速度（#3.5）

新增「快速回复模式」开关（设置 → 聊天 → 交互行为）。
开启后跳过每轮的相关记忆检索——这一段的 FTS 查询在真机实测可占 500ms+ 首字延迟。
长期记忆仍照常注入，只是不做按问题的相关性召回。

---

## English

### Critical: Settings → Chat crashed

v2.6.2 crashed when opening Settings → Chat (triggered on scrolling down to the
sticker section) with `IllegalFormatConversionException: d != java.lang.String`.

The format string was passed as an argument to `stringResource(id, "%1$d")`,
which executes `String.format` immediately and put a String into an integer slot.
Fixed by using the argument-less `stringResource`. The same pattern in the
"forwarded" toast (which silently showed a literal placeholder) is fixed too.

Not present in v2.6.1 or earlier.

### WebServer failed to start (#4)

`Array has more than one element` is Ktor 3.0.x's known release-only defect under
R8 (KTOR-7298); it never reproduces in debug builds.

- Ktor 3.0.3 → 3.1.3 (official fix)
- Added R8 keep rules for ServiceLoader / engine factory as a second layer

### Silent tool-call failures (#6)

**#6**: In group chats, `channel_reply` validation failures were discarded, so the
round silently ended as `implicit pass`. Now failures are fed back to the model for
a bounded retry, empty responses are no longer treated as an explicit skip, and
`[PASS]` is distinguished from an empty response in logs.

> **Note on #5**: When a model claims a tool call but returns no valid `tool_call`,
> reasoning content is still shown as the reply body. That is an existing product
> decision ("something is better than blank") which conflicts with "don't show
> thinking as body text", so it needs a separate decision. This release does **not**
> change that behavior, to avoid shipping an undecided semantic change in a hotfix.

### Relay-hosted multimodal models misjudged (#3.1)

The built-in capability table unconditionally overrode upstream declarations, so
relay models that genuinely support images were marked text-only. Upstream
`image` declarations are now respected. You can still override per model under
Provider → Model → Edit capabilities, and manual edits always win.

### Provider "Test" false negatives (#3.2)

- `maxTokens` 16 → 256 (16 was not enough for reasoning models; the budget was
  consumed entirely by reasoning, leaving empty content)
- Tool calls or reasoning-only output now count as a healthy link
- Failures report an actionable cause instead of a raw upstream string

### Error text truncated and not copyable (#3.3)

Error detail is no longer truncated to 80 characters.

### Termux permission flow (#3.4)

- Step-by-step guidance after jumping to the app info page
- Config hint now includes both writing the property **and** `termux-reload-settings`
- Added a "copy command" button

### Reply latency (#3.5)

New "fast reply mode" toggle (Settings → Chat → Interaction). It skips the
per-turn relevant-memory retrieval, whose FTS query measured 500ms+ of
time-to-first-token on device. Long-term memory is still injected.

---

## 安装

- 覆盖安装即可，**无需卸载**，数据保留
- 数据库版本仍是 **111**，与 v2.6.2 一致，**无迁移风险**
- 签名与 v2.6.1 / v2.6.2 相同

## 已知事项

- 若你从 v2.6.1 直接升级到本版，仍会走一次 v110 → v111 迁移（新增助手人格/思考链两列），属正常
- 「快速回复模式」默认关闭，需要更快首字时再打开
