# Muse v2.6.28

## 本次更新

### 装一次，体积小一半

- Node 和 Python 不再打进安装包，改为首次用到时按需下载运行环境。arm64 安装包从 148MB 降到 67MB。
- 首次用到脚本类工具（Node / Python 脚本、MCP 服务）时，聊天里会出现一张安装卡：下载、校验、解压都有进度，装好会自动继续刚才中断的任务。
- 不想现在装也没关系，点「以后再说」会收成一条细条留在会话里，随时可以再点开。

### 供应商配置不用再看文档

- 自定义供应商只需要填一个接口地址和 Key，点「测试并自动识别」，Muse 会依次尝试几种常见协议，哪套能通就直接用哪套并保存。
- 地址带不带 `/v1`、是不是把完整接口路径粘贴进来了，都不用自己判断，程序会自己试。
- 顺手会探一次向量接口的路径，能通就一起存好；不通也不影响聊天。

### 联网搜索说真话

- 以前搜索服务的 Key 无效时，界面看起来像「搜了但没有结果」，AI 于是凭记忆编答案。现在会直接说明是哪个服务、出了什么错，比如「Bocha 返回 HTTP 401（API Key 无效或无权限）」。
- 额度不足、地址写错、服务端故障，都会给出对应的原因，不再笼统报「网络异常」。

### 余额不足不再假装网络问题

- 账户余额不足时，以前会被当成网络错误并自动重试三次。现在直接告诉你余额或额度不足，不再白等。
- 推理类模型在输出预算不足时会先花光 token 思考、正文一个字都写不出来。现在会明确提示是预算不够，而不是「模型没有回应」。

### 记忆整理（Dream）

- 记忆页新增「整理记忆」入口：让模型重新归纳事实、今日、本周、长期四段记忆。
- 每次整理都会留一份快照，不满意可以一键回滚到上一版。
- 模型整理的结果不会直接写入：格式不对、段落不认识、内容过长，整份作废并保持原样，不会把记忆改坏。
- 记忆页现在能看到每段内容的来源状态和更新时间，能分辨「这是当前记忆」还是「上次失败留下的旧内容」。

### 本地语音识别修好了

- 修复长按麦克风没反应、要点很多次才成功的问题。
- 修复松手后立即闪退的问题（打包优化误删了识别引擎的关键代码，导致系统直接终止应用）。
- 修复识别引擎参数类型错误导致「录到了音但一个字都不出」的问题。
- 语音输入现在说完自动发送，不需要再点一次发送键。
- 识别失败时会显示具体原因，不再静默无反应。
- 长按输入栏不再弹出全屏输入菜单（它会和麦克风抢手势）；全屏输入入口移到「+」菜单。

### 其他修复

- 修复每日总结、问候语在推理模型上经常生成空白的问题。
- 统一了经验库的存储位置：工具记下的经验，提示词和记忆页现在都能看到。
- 修复了若干稳定性问题。

---

# Muse v2.6.28 (English)

## What's New

### Half the download size

- Node and Python are no longer bundled. The runtime downloads on first use. The arm64 APK drops from 148MB to 67MB.
- When a script-based tool is first used, an install card appears in the chat with download, verify, and extract progress. It resumes the interrupted task once ready.
- Choose "Later" to collapse it into a compact row that stays in the conversation and can be reopened anytime.

### Provider setup without reading docs

- For a custom provider, enter just a base URL and key, then tap "Test and auto-detect". Muse tries the common protocols in turn and saves whichever works.
- Whether the URL needs `/v1`, or you pasted a full endpoint path, is detected automatically.
- It also probes the embeddings endpoint once and stores the working path when available.

### Web search tells the truth

- Previously an invalid search key looked like "searched but found nothing", so the model answered from memory. Now the actual service and error are reported, e.g. "Bocha returned HTTP 401 (invalid or unauthorized API key)".
- Quota, bad URL, and server errors each get their own explanation instead of a generic network error.

### Insufficient balance is no longer disguised as a network error

- A 402 response used to be treated as a network failure and retried three times. It now reports insufficient balance or quota directly.
- Reasoning models could burn the whole output budget on thinking and produce no text. This is now reported as a budget problem rather than "the model did not respond".

### Memory tidy-up (Dream)

- The memory page gains a "Tidy memory" action that re-summarizes the facts, today, week, and long-term sections.
- Every run keeps a snapshot, so you can roll back to the previous version.
- Model output is never written directly: unknown sections, blank or oversized content reject the whole plan and leave memory untouched.
- Each section now shows its source status and update time, so you can tell current memory from a stale leftover.

### Local speech recognition fixed

- Fixed the microphone requiring many attempts before it responded to a long press.
- Fixed an immediate crash on release (the optimizer stripped code the recognition engine needs, so the system terminated the app).
- Fixed a parameter type error that made recognition return nothing despite recording audio.
- Voice input now sends automatically when you release, no extra tap needed.
- Failures now show the actual reason instead of staying silent.
- Long-pressing the input bar no longer opens the full-screen editor (it was competing with the microphone gesture); the entry moved into the "+" menu.

### Other fixes

- Fixed daily summaries and greetings frequently coming back empty on reasoning models.
- Unified experience storage so entries written by tools now appear in both the prompt and the memory page.
- Fixed several stability issues.
