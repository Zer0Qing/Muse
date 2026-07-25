<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# ToolRegistry 内置工具

ToolRegistry 是 muse 的本地工具注册表(简化版 MCP 框架),工具可被 LLM 通过 function calling / tool_call 触发,也可被定时任务、Skill、WebServer 复用。当前共 25 个内置工具(以源码 ToolRegistry.kt 的 registerBuiltIn 为准,本文档仅列常用部分)。

## 可用工具列表(常用工具,完整列表见源码)

### 通用工具(原 7 个)
- get_current_time: 获取当前时间(可选 timezone 参数,IANA 时区)
- calculator: 简易计算器,支持加减乘除和括号(参数 expression)
- echo: 回显输入(测试用,参数 text)
- clipboard_read: 读取系统剪贴板文本
- clipboard_write: 写入系统剪贴板(参数 text)
- screen_time: 今日各应用屏幕使用时间 Top 10(需 PACKAGE_USAGE_STATS 特殊权限)
- calendar_today: 今日日历事件列表(需 READ_CALENDAR 运行时权限)

### 手机端工具(v0.47 新增 7 个,Android 系统 API 实现)
- set_alarm: 设置系统闹钟(参数: hour 0-23 必填, minute 0-59 必填, label 可选)
  - 通过 AlarmClock.ACTION_SET_ALARM 拉起系统时钟应用,无需运行时权限
- set_timer: 设置系统倒计时(参数: seconds 必填, label 可选)
  - 通过 AlarmClock.ACTION_SET_TIMER 拉起系统时钟应用,无需运行时权限
- open_app: 打开应用(参数: packageName 应用包名,如 com.tencent.mm)
  - 通过 PackageManager.getLaunchIntentForPackage 启动应用主界面
- share_text: 分享文本(参数: text)
  - 通过 ACTION_SEND + createChooser 弹出系统分享选择器
- get_location: 获取当前粗略位置(返回 纬度/经度/精度)
  - 读取系统最后已知位置,需 ACCESS_COARSE_LOCATION 运行时权限
  - 不主动申请权限、不开启 GPS;真正的实时定位需 LocationCallback + Activity,本期不做
- get_device_info: 获取设备信息(品牌/型号/Android 版本/屏幕分辨率/电量)
  - 无需权限
- get_contacts_count: 获取通讯录联系人数量
  - 需 READ_CONTACTS 运行时权限,只读取数量不读取详情

## 调用方式
LLM 通过 tool_call 触发,arguments 为 JSON 对象:
```json
{
  "name": "set_alarm",
  "arguments": {"hour": 8, "minute": 30, "label": "起床"}
}
```

ChatService 会把 LLM 返回的 tool_call 转交给 `ToolRegistry.executeFromJson(name, argumentsJson)`,该方法解析 JSON 后调 `execute(name, args)`,返回字符串结果回灌给 LLM。

工具定义通过 `ToolRegistry.listToolsAsToolDefinitions()` 生成 OpenAI 兼容的 ToolDefinition 列表,注入对话请求的 tools 字段。

## 权限说明
- get_location 需要 ACCESS_COARSE_LOCATION(已在 AndroidManifest 声明,运行时需用户授权)
- get_contacts_count 需要 READ_CONTACTS(已在 AndroidManifest 声明,运行时需用户授权)
- screen_time 需要 PACKAGE_USAGE_STATS(特殊权限,需在设置中授予)
- calendar_today 需要 READ_CALENDAR
- set_alarm / set_timer / open_app / share_text / get_device_info 无需运行时权限
- 权限不足时工具返回提示字符串,不会崩溃,也不会强制弹权限框

## 调用建议(LLM 选工具的启发式规则)
- 用户说"提醒我 X 点做 Y" / "设个 X 点的闹钟" → set_alarm
- 用户说"X 分钟后提醒我" / "倒计时 X 秒" → set_timer
- 用户说"打开微信/QQ/应用名" → open_app(packageName=com.tencent.mm / com.tencent.mobileqq 等)
- 用户说"分享这段话" / "把这段发出去" → share_text
- 用户问"我在哪" / "我的位置" → get_location
- 用户问"我手机信息" / "设备信息" / "电量多少" → get_device_info
- 用户问"我有多少联系人" / "通讯录多少人" → get_contacts_count

## 常见应用包名参考
- 微信: com.tencent.mm
- QQ: com.tencent.mobileqq
- 支付宝: com.eg.android.AlipayGphone
- 抖音: com.ss.android.ugc.aweme
- 淘宝: com.taobao.taobao
- 设置: com.android.settings

## 不实现的工具及原因
- send_sms: 会主动发短信产生费用,风险高,本期跳过
- add_contact: 会修改用户通讯录数据,风险高,本期跳过

## 工具调用最佳实践(LLM 必读)

### 何时调用工具
- **用户意图明确指向工具能力时**:如"几点了""设个 8 点闹钟""搜一下今天新闻"→ 直接调对应工具,不要先回复"我来帮你查"再调,应一次性返回工具结果 + 简短解读。
- **信息不足时主动补全**:如用户说"打开微信"→ 直接 `open_app(packageName=com.tencent.mm)`,不需要再问"微信的包名是什么"。
- **不要为了"显得能干"而滥用**:简单闲聊、情感陪伴、观点讨论不需要调任何工具。

### 参数填写规范
- **必填参数不能省略**:`set_alarm` 的 hour/minute、`web_search` 的 query、`generate_image` 的 prompt 都是必填,缺失会返回 `skill_missing_param_*` 错误。
- **数值参数注意范围**:`hour` 必须 0-23,`minute` 必须 0-59,`top_k`/`max_results` 一般 1-10/1-50。超范围会被 `coerceIn` 截断,但应尽量给准确值。
- **JSON 参数需序列化为字符串**:`install_skill` 的 `skill_json`、`http_post` 的 `body` 都应是 JSON 字符串,不是嵌套对象。
- **路径参数用相对路径**:`read_file`/`write_file` 的 `path` 相对 `filesDir`,不要写 `/data/data/...` 绝对路径。
- **URL 参数带协议前缀**:`http_get`/`web_fetch` 的 `url` 必须带 `http://` 或 `https://`。

### 多工具协作模式
- **先搜后读**:`web_search` 拿到 URL 列表 → 选最相关的 → `web_fetch` 抓全文。不要试图用 `web_search` 一次性拿到完整内容(它只返回摘要)。
- **先查再答**:`knowledge_search` 查用户已导入的文档 → 基于检索片段回答,不要凭对话上下文记忆编造文档内容。
- **先算后回**:`calculator` 验证数学计算,避免 LLM 心算出错。涉及金额、日期、单位换算时建议调用。
- **委托分工**:`delegate_agent` 把子任务交给专门助手(如翻译交给翻译助手),主助手聚合结果。

### 工具调用失败处理
- **权限不足**:工具会返回提示字符串(不崩溃)。应向用户说明需要授权,并指引去设置(如"请在系统设置中授予通讯录权限")。
- **网络异常**:`http_get`/`web_search` 失败时,不要假装成功,应坦诚告知"网络异常,稍后重试"。
- **参数错误**:返回 `skill_missing_param_*` 时,检查参数名拼写和是否为空。
- **工具未配置**:`generate_image` 未配置 ImageService 时返回 `skill_image_not_configured`,应提示用户去 设置 → 模型与服务 → 图片生成 配置。

### 工具调用与回复的关系
- 工具调用后,结果会回灌给 LLM,LLM 应基于结果生成自然语言回复,不要直接把工具返回的原始字符串贴给用户。
- 例外:`calculator` 的结果可以直接嵌入回复(如"3 + 5 = 8"),`translate` 的结果就是翻译文本可直接返回。
- 涉及多步骤工具调用时,可以在回复中简述流程(如"我先搜了 X,然后读了 Y 的全文"),让用户理解回答的依据。

## 工具调用风险等级与审批

部分工具有风险等级,首次调用会弹审批卡片让用户确认:
- **高风险**:可能产生外部副作用或费用,如 `set_alarm`(改系统闹钟)、`http_post`(提交数据)。
- **中风险**:访问敏感数据,如 `get_location`、`get_contacts_count`、`clipboard_read`。
- **低风险**:只读或本地操作,如 `get_current_time`、`calculator`、`web_search`。

用户可在 设置 → 工具 中调整每个工具的审批策略(每次询问 / 本次会话允许 / 永久允许)。LLM 不应假设工具一定被批准,用户拒绝时尊重决定,不要反复尝试。

## 常见误用与纠正
- ❌ 用 `echo` 测试工具是否可用 → ✅ 直接调用目标工具,失败信息会告诉你原因
- ❌ 用 `web_search` 查询当前时间 → ✅ 用 `get_current_time`(更准更快)
- ❌ 用 `http_get` 抓搜索引擎结果页 → ✅ 用 `web_search`(已封装解析逻辑)
- ❌ 一次调用 `web_fetch` 多个 URL → ✅ 一次一个 URL,串行调用
- ❌ 把 `reference_image` 参数硬编码为 URL → ✅ 该参数由用户在审批卡片从相册选择后注入,LLM 不应主动填
