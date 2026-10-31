# Muse v2.6.18

> 这一版把设置按「你来干什么」重排到 14 类，把主动消息里四个从来没生效的配置真正接上，
> 让克隆过的音色终于能发出声，让记忆摘要里读得到的东西变得搜得到；
> 同时清掉几处发版前自检挖出来的硬伤。

本版合并了 v2.6.10 之后到现在的全部改动。

---

## 中文

### 设置页按「你来干什么」重排到 14 类

上一版是 12 类，这一版按最细设置项重新分了一遍，扩到 14 类、48 项：

- 新增「隐私与安全」，把分享与安全从数据里拆出来
- 代理并入「连接」，不再单独占一类
- 原「数据与隐私」拆成「数据与备份」和「隐私与安全」
- 每个分类不超过 5 项，开发者选项不折叠

### 主动消息：四个配置这次真的接上了

调节间隔、随机偏移、发送概率、让 AI 决定节奏，这四项以前在界面上能改，
但调度根本不读。现在接了，界面与行为一致：

- 默认间隔 240 分钟，与界面写的「约 4 小时」对齐
- 素材总预算 6000 字，输出硬截断 200 字
- 「让 AI 决定节奏」从界面移除，它描述的能力没有实现
- 未响应闭环：通知里点「这次不用」记为不感兴趣，打开会话记为已查看，
  24 小时未响应记为过期，都不再参与下一轮判断
- 多次不受欢迎的类型不再进入候选

### 语音：配好就能发

之前「克隆了音色、角色还是不发语音」有几个原因叠在一起，逐条修掉：

- 语音克隆页现在会把手填的 API Key 一起保存。以前只存引擎和音色，
  云端合成必然因为缺少凭据失败
- 云端失败不再静默回落到系统音色还报成功。工具结果会写明
  「已发出语音消息，但当前用的是手机自带音色」，并带上云端失败的原因
- 切换 TTS 引擎时，不属于新引擎的音色会被清掉并提示重选
- TTS 与 ASR 的模型改成从供应商模型列表里挑，不再手填
- 发语音成为常驻工具。以前它属于按需装载，说话里不带「语音」这类词就不会出现，
  自定义助手设过工具白名单时更是连检索都搜不到

### 记忆：摘要里读得到的，现在搜得到

长期记忆摘要注入提示词之后，用 `search_memory` 搜「子奇 开发者 助手」是零命中。
原因是检索层是行级全文匹配，中文按二元切词后用空格连接，等于要求所有词同时出现在
同一行事实里；而摘要是多行事实加上模型改写的散文。

- 多词查询改成两段式：先按精确语义检索，结果不足再按任一命中放宽合并
- 查询侧去掉只增加约束、不增加召回的尾单字
- 模糊回退按词拆开匹配，不再拿整串当子串
- 事实表没有结果时回落到编译摘要做子串扫描，命中项标注来源是「编译摘要」

### 工具面

- **JS 沙盒整条链路不可用**：沙盒启动前的环境自检脚本少了一个 `return`，
  回调拿到的是空值，于是自检永远判失败。受影响的不只是 `execute_javascript`，
  插件激活与 JS 技能求值一起废掉。修好后错误分类也分开了：
  环境不可用时会明说「重试同一段代码不会成功」，不再让模型以为是参数写错
- **长输出能找了**：`tool_outputs` 目录登记成只读挂载，`workspace_list` 能列出来，
  回执里写的路径改成挂载写法。以前文件名带随机串，回执一丢就只能猜
- **工具计数统一**：提示词写「已注册 240 个」、`find_tools` 报 231、启动日志报 172，
  三个数来自三个不同集合。现在收敛到一处定义，提示词改成「本轮可调用 N 个」
- **Node 运行时**：`os.cpus()` 在某些系统上返回空数组，按核数决定并发的 npm 包
  会拿到 0。现在注入 CPU 数并在取不到时合成，运行自检也加了这一项探测

### 修掉的问题

- **启动崩溃**：设置分组页把枚举当导航参数，R8 改名之后一构造路由就抛
  `Cannot find class with name`。改成字符串 id，目标页再映射
- **焦点恢复偶发丢失**：会话切换守卫是进程级单例，一个界面实例的迟到切换
  会把另一个实例刚发起的切换判成过期。改成实例级
- **插件装不上**：安装时的版本门槛读的是构建参数，本地构建不带版本号时
  是哨兵值，比插件要求的版本小。现在哨兵版本跳过这道门槛并记警告，
  正式包照旧校验，降级与签名检查不受影响

### 安装

- `arm64-v8a`：绝大多数手机
- `armeabi-v7a`：老设备
- `x86_64`：模拟器
- `universal`：不确定机型时装这个

覆盖安装即可，版本号递增，不会触发降级拒绝。

### 已知事项

- 云端 TTS 回落到系统音色时，声音是手机自带的，工具结果里会说明原因
- 云端合成返回的 mp3 存放在 `.wav` 后缀下，播放正常
- 删除消息后语音文件不会立刻回收，随缓存清理

---

# Muse v2.6.18 (English)

> This release reorganizes settings into 14 groups by what you came to do, makes four
> proactive-message settings actually take effect, gets cloned voices to speak, makes
> what the memory summary shows searchable, and clears several hard failures found by
> the pre-release self-check.

This release bundles everything since v2.6.10.

## Settings reorganized into 14 groups

The previous release had 12 groups. This one splits by the finest setting unit
into 14 groups and 48 entries:

- New "Privacy & security", separating sharing and security from data
- Proxy merged into "Connection"
- "Data & privacy" split into "Data & backup" and "Privacy & security"
- No group exceeds five entries; developer options stay expanded

## Proactive messages: four settings now actually work

Interval, random offset, send probability, and "let the model pace it" were editable
but never read by the scheduler. They are wired now and the UI matches the behavior:

- Default interval 240 minutes, matching the "about 4 hours" label
- Material budget 6000 characters, output hard-truncated at 200
- "Let the model pace it" removed from the UI; the capability did not exist
- Feedback loop: "not this time" in the notification marks it dismissed, opening
  the session marks it seen, 24 hours without a response marks it expired
- Repeatedly unpopular categories stop entering the candidate pool

## Voice: configure it and it sends

Several causes stacked up behind "the voice was cloned but the character never speaks":

- The cloning page now saves the API key you typed. It previously saved only the
  engine and voice, so cloud synthesis always failed for lack of credentials
- Cloud failure no longer silently falls back to the system voice and reports success.
  The tool result says the message was sent with the phone's built-in voice and
  includes the reason cloud synthesis failed
- Switching TTS engines clears voices that do not belong to the new engine
- TTS and ASR models are picked from the provider model list instead of typed by hand
- Sending voice is now an always-on tool. It used to load on demand, so it was
  invisible unless you said "voice", and assistants with a tool allowlist could not
  even find it

## Memory: what the summary shows is now searchable

After the long-term summary was injected into the prompt, searching
"name developer assistant" with `search_memory` returned nothing. Retrieval matched
whole rows: Chinese is split into bigrams joined by spaces, which requires every term
to appear in the same row, while the summary is many rows plus model-written prose.

- Multi-term queries now run in two stages: exact first, then a relaxed union when
  results fall short
- Query-side single-character tail tokens removed; they only added constraints
- Fuzzy fallback splits the query into terms instead of matching the whole string
- When the fact table is empty, retrieval scans the compiled summary and labels those
  hits as coming from the summary

## Tool surface

- **The JS sandbox never ran**: the pre-flight self-check script was missing a
  `return`, so the callback received nothing and verification always failed. That took
  down `execute_javascript`, plugin activation, and JS skill evaluation together.
  Error classification is now split as well: an unavailable environment says retrying
  the same code will not help, instead of looking like a bad argument
- **Long output is discoverable**: `tool_outputs` is a read-only mount, listed by
  `workspace_list`. The receipt now uses the mount path. File names contain random
  strings, so a lost receipt used to mean guessing
- **Tool counts unified**: the prompt said 240 registered, `find_tools` reported 231,
  and the startup log said 172, each from a different set. They now come from one
  definition and the prompt says "N callable this round"
- **Node runtime**: `os.cpus()` returns an empty array on some systems, so npm
  packages sizing concurrency by core count got 0. The CPU count is injected and
  synthesized when unavailable, and the runtime self-check probes it

## Fixed

- **Startup crash**: the settings group route used an enum as a navigation argument.
  After R8 renamed it, building the route threw `Cannot find class with name`. It now
  carries a string id and maps back at the destination
- **Focus restore occasionally lost**: the session switch guard was a process-wide
  singleton, so a late switch in one UI instance invalidated a fresh switch in another.
  It is instance-scoped now
- **Plugins refused to install**: the minimum app version check read the build
  parameter, which is a sentinel in local builds. Sentinel builds now skip that gate
  with a warning; release builds still enforce it, as do downgrade and signature checks

## Installation

- `arm64-v8a`: most phones
- `armeabi-v7a`: older devices
- `x86_64`: emulators
- `universal`: pick this if unsure

Overwrite install works; the version code increases.

## Known issues

- When cloud TTS falls back to the system voice, the result explains why
- Cloud mp3 output is stored under a `.wav` extension; playback is fine
- Voice files are not reclaimed immediately when a message is deleted