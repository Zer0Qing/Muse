# Muse v2.6.19

> 这一版做的是「把该省的工作省掉，把该有的反馈补上」：
> 四条链路的性能整改、JS 沙盒终于能真的联网、编辑消息后分支就地展开、
> 主动消息不再一股脑往 Agent 页塞。凡是没有测量支撑的说法，都从文档里撤掉了。

本版合并了 v2.6.18 之后到现在的全部改动。

---

## 中文

### 性能：三条有证据的收益

| 指标 | 改前 | 改后 |
|---|---|---|
| 渠道积压 50 条带图消息，45 秒内磁盘写入 | 479.9 MB | **10.6 MB（45×）** |
| 每轮对话的客户端开销（提示词组装 + 管道） | 约 530 ms | **约 355 ms（−33%）** |
| 安装包体积 | — | **每个 ABI −2.96 MB** |

- 收件箱、会话、卡片数据都改成按条目/按会话分片落盘，不再「处理一条消息重写整个收件箱」；
- 每轮里那些「算一遍只为确认真没变」的工作（工具清单、经验库、上下文占用、压缩 token）改成算一次记住；
- 日志文件改成常驻句柄，不再每行开关一次文件。

同时**撤回**了三条曾经写在文档里、但被测量推翻的说法：卡片整页重载 68→9、卡片滚动帧 36→34 ms、
8 张卡内存 −7.5%。用 n=3 复测后它们都没有显著差异 —— 代码保留，但不再计入收益。

### JS 沙盒：网络与异步返回值

- **fetch / XMLHttpRequest 由宿主原生发起**，不受浏览器同源策略限制（沙盒页面 origin 是 null，
  以前任何跨站请求都会被 CORS 拦成失败）。每次外发都会写审计日志：方法、主机、体积、状态码。
- **脚本可以 `return Promise`**：以前 async 函数返回的是空对象 `{}`，成功和失败完全不可区分；
  现在会等 Promise 完成再返回真实结果，异常也会进 `error` 字段。
- 脚本返回值不再被二次编码：`return {a:1}` 与 `return '{"a":1}'` 都只编码一次。
- 顺带修掉一个一直存在的 bug：沙盒初始化脚本只在建 WebView 时注入一次，
  页面加载后 `host.getConfig()` 等覆盖其实已经失效 —— 现在每次执行前重新注入。

### 对话：编辑消息就地分支

以前编辑中间某条用户消息后，新回复会跑到会话末尾；现在会**停在原位置**形成分支，
并显示「2/2」变体标记，可以左右切换 —— 和切换对话树是同一套行为。

### 复制按钮：只复制正文

以前「复制」会把思考过程一起拼在正文后面（还带一个 `[思考过程]` 标记），
看起来像「复制出来的是思考」。现在只复制正文；正文为空时才回退到思考。
想要思考有单独的「复制思考」。

### 主动消息：落点与开关

- 落点优先**普通对话会话**（最近有用户发言的那个），不再因为 Agent 页更新更晚就抢过去；
- 只在 Agent 里聊过时，写回**那个已有的 Agent 会话**，不再每次都新建一个空的；
- 设置里那个「仅 Agent 会话」开关**以前没有任何代码读它**（怎么切都没反应），
  现在改成真正的「Agent 会话」开关：关掉后主动消息只发普通会话，宁可不发也不落进 Agent 页。

### 其它

- `read_file` 失败时会列出同目录现有文件（以前提示你去 `workspace_list` 列目录，而那个动作根本够不着 `tool_outputs/`）；
- 明文 HTTP 放开：沙盒要访问任意 http 主机，而 Android 的明文策略按域名放行、无法只对沙盒开口子，
  因此全局放开（Provider 走 http 时日志仍会告警）。想收回把 `network_security_config.xml` 里的 `true` 改回 `false` 即可。

### 已知问题

- **帧率类指标仍需真机验证**：模拟器（含 GPU 直通）的帧时间下限约 32 ms，比要验证的差异还大，
  卡片滚动流畅度在模拟器上量不出差别；
- 工具数量口径不一致（提示词索引 234 vs `find_tools` 231），待单独修；
- 沙盒 WebSocket 仍未支持（fetch/XHR 已可用）。

---

# Muse v2.6.19 (English)

> This release is about *removing work that did not need to happen* and *making feedback honest*:
> performance work across four pipelines, a JS sandbox that can finally reach the network,
> in-place branching when you edit a message, and proactive messages that stop landing in the Agent tab.
> Anything that measurement did not support was removed from the docs.

### Performance: three claims with evidence

| Metric | Before | After |
|---|---|---|
| Disk writes with 50 queued image messages (45 s) | 479.9 MB | **10.6 MB (45×)** |
| Per-turn client cost (prompt assembly + pipeline) | ~530 ms | **~355 ms (−33%)** |
| APK size | — | **−2.96 MB per ABI** |

Three earlier claims were **retracted** after n=3 re-measurement: card full-page reloads 68→9,
card scroll frame time 36→34 ms, and 8-card memory −7.5%. None showed a significant difference.
The code stays; the claims do not.

### JS sandbox: network and async results

- `fetch` / `XMLHttpRequest` are now performed **natively by the host**, so the browser's
  same-origin policy (the sandbox page has a null origin) no longer blocks cross-site requests.
  Every outbound request is audited: method, host, size, status.
- Scripts may `return Promise`. Previously an async function returned `{}` and success was
  indistinguishable from failure; now the promise is awaited and rejections surface in `error`.
- Return values are no longer double-encoded.
- Fixed a long-standing bug where the sandbox prelude was injected only once at WebView creation
  and silently lost after page load (`host.getConfig()` was affected too).

### Chat: editing a message branches in place

Editing a user message used to move the new reply to the end of the conversation. It now branches
**in place**, with a `2/2` variant badge you can switch between — the same behaviour as switching trees.

### Copy: body only

Copy used to append the reasoning (with a `[thinking]` marker), which looked like "it copied the thinking".
It now copies the body only, falling back to reasoning when the body is empty. Reasoning has its own button.

### Proactive messages: target and switch

- Targets the most recent **normal** chat session first, instead of jumping to the Agent tab;
- If you only ever chatted in the Agent tab, it reuses that session instead of creating a new one every time;
- The old "Agent sessions only" switch had **no reader anywhere** (toggling it did nothing).
  It is now a real "Agent sessions" switch: when off, proactive messages only go to normal sessions.

### Other

- `read_file` failures now list the files that actually exist in that directory;
- Cleartext HTTP is allowed app-wide (the sandbox needs arbitrary http hosts and Android's policy
  is per-domain). Flip `cleartextTrafficPermitted` back to `false` to revert.

### Known issues

- Frame-time metrics still require a physical device: the emulator's frame floor (~32 ms) is larger
  than the differences being measured;
- Tool-count reporting is inconsistent (index 234 vs `find_tools` 231);
- WebSocket is still unsupported inside the sandbox.
