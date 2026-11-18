# Muse v2.6.28

> 本版改动较多，按主题分组：安装体积、离线资源、供应商配置、联网搜索、错误提示、记忆整理、本地语音识别、其他修复。

## 一、安装包体积减半

- **Node 与 Python 不再打进安装包**，改为首次用到时按需下载运行环境。arm64 安装包从 148MB 降到 67MB。
- 首次用到脚本类工具（Node / Python 脚本、MCP 服务）时，聊天里会出现一张安装卡：下载、校验、解压都有进度，装好会自动继续刚才中断的任务。
- 不想现在装可以点「以后再说」，收成一条细条留在会话里，随时能再点开。
- 运行时缺失时，工具会明确告诉 AI「缺运行环境」，AI 不会反复重试同一个工具。

## 二、下载类资源统一入口

- 设置首页新增一级入口「**离线资源**」，运行环境和本地语音模型都在里面，不用再分别去「数据与备份」和「语音识别」里找。
- 语音识别页的「模型下载」也指向这里，两处入口通向同一页面。

## 三、供应商配置不用再看文档

- 自定义供应商只需填接口地址和 Key，点「测试并自动识别」，Muse 会依次尝试几种常见协议，哪套能通就直接用哪套并保存。
- 地址带不带 `/v1`、是不是把完整接口路径粘贴进来了，都不用自己判断。
- 顺手会探一次向量接口路径，能通就一起存好；不通也不影响聊天。
- 聊天时如果服务端不支持 `/responses`，会自动改用 `/chat/completions` 并记住，不用手动改开关。

## 四、联网搜索说真话

- 搜索服务的 Key 无效时，以前界面看起来像「搜了但没有结果」，AI 于是凭记忆编答案。现在会直接说明是哪个服务、出了什么错，例如「Bocha 返回 HTTP 401（API Key 无效或无权限）」。
- 额度不足、地址写错、服务端故障，都会给出对应原因，不再笼统报「网络异常」。

## 五、错误提示不再误导

- 账户余额不足时，以前被当成网络错误并自动重试三次。现在直接说明余额或额度不足。
- 推理类模型输出预算不足时，会先花光 token 思考、正文一个字都写不出来。现在会明确提示是预算不够，而不是「模型没有回应」。

## 六、记忆整理（Dream）

- 记忆页新增「整理记忆」入口：让模型重新归纳事实、今日、本周、长期四段记忆。
- 每次整理都会留一份快照，不满意可一键回滚。
- 模型整理的结果不会直接写入：格式不对、段落不认识、内容过长，整份作废并保持原样。
- 记忆页能看到每段内容的来源状态和更新时间，能分辨「当前记忆」和「上次失败留下的旧内容」。
- 经验库存储位置统一：工具记下的经验，提示词和记忆页现在都能看到。

## 七、本地语音识别

- 修复长按麦克风没反应、要点很多次才成功的问题。
- 修复松手后立即闪退的问题（打包优化误删了识别引擎的关键代码，系统直接终止应用）。
- 修复识别引擎参数类型错误导致「录到了音但一个字都不出」的问题。
- 语音输入现在**说完自动发送**，不需要再点一次发送键。
- 识别失败时会显示具体原因，不再静默无反应。
- 长按输入栏不再弹出全屏输入菜单（它会和麦克风抢手势）；全屏输入入口移到「+」菜单。

## 八、其他修复

- 修复每日总结、问候语在推理模型上经常生成空白的问题。
- 修复若干稳定性问题。

---

# Muse v2.6.28 (English)

> This release is grouped by topic: install size, offline resources, provider setup, web search, error messages, memory tidy-up, local speech, and other fixes.

## 1. Half the download size

- **Node and Python are no longer bundled.** The runtime downloads on first use. The arm64 APK drops from 148MB to 67MB.
- When a script-based tool is first used, an install card appears in the chat with download, verify, and extract progress. It resumes the interrupted task once ready.
- Choose "Later" to collapse it into a compact row that stays in the conversation.
- When the runtime is missing, tools tell the model so it stops retrying the same call.

## 2. One place for downloads

- Settings gains a top-level "**Offline resources**" entry containing both the runtime and the local speech model.
- The speech settings page links to the same screen.

## 3. Provider setup without reading docs

- For a custom provider, enter a base URL and key, then tap "Test and auto-detect". Muse tries the common protocols and saves whichever works.
- Whether the URL needs `/v1`, or you pasted a full endpoint path, is detected automatically.
- It also probes the embeddings endpoint and stores the working path when available.
- If the server does not implement `/responses`, chat falls back to `/chat/completions` and remembers it.

## 4. Web search tells the truth

- An invalid search key used to look like "searched but found nothing", so the model answered from memory. The actual service and error are now reported, e.g. "Bocha returned HTTP 401 (invalid or unauthorized API key)".
- Quota, bad URL, and server errors each get their own explanation.

## 5. Error messages no longer mislead

- A 402 response used to be treated as a network failure and retried three times. It now reports insufficient balance or quota directly.
- Reasoning models could burn the whole output budget on thinking and produce no text. This is now reported as a budget problem rather than "the model did not respond".

## 6. Memory tidy-up (Dream)

- The memory page gains a "Tidy memory" action that re-summarizes the facts, today, week, and long-term sections.
- Every run keeps a snapshot, so you can roll back.
- Model output is never written directly: unknown sections, blank or oversized content reject the whole plan.
- Each section shows its source status and update time, so you can tell current memory from a stale leftover.
- Experience storage is unified, so entries written by tools appear in both the prompt and the memory page.

## 7. Local speech recognition

- Fixed the microphone requiring many attempts before it responded.
- Fixed an immediate crash on release (the optimizer stripped code the engine needs, so the system terminated the app).
- Fixed a parameter type error that made recognition return nothing despite recording audio.
- Voice input now **sends automatically** when you release.
- Failures show the actual reason instead of staying silent.
- Long-pressing the input bar no longer opens the full-screen editor; that entry moved into the "+" menu.

## 8. Other fixes

- Fixed daily summaries and greetings frequently coming back empty on reasoning models.
- Fixed several stability issues.
