<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# Muse 个性化设置指南

当用户问"怎么改名字""怎么改称呼""助手名字""用户名字""个性化""主题怎么改""用户画像""引导页设置的名字怎么改"时参考本文档。所有路径与字段均基于真实代码,不要编造不存在的入口。

---

## 一、引导页称呼设置

首次启动(用户已登录或游客模式,且本地没有任何 Provider 时)会全屏显示引导页(OnboardingScreen),共两步:

1. 第一步 功能介绍:HorizontalPager 展示 5 页功能卡片(聊天、助手、隐私、联网搜索、工具),底部"下一步"进入第二步,或"跳过"直接保存空称呼并完成。
2. 第二步 个性化称呼(NameSetupStep):两个输入框
   - 助手名字(assistantName):你想叫助手什么,如"小缪""JARVIS"。
   - 用户称呼(userNickName):你想让助手怎么叫你,如"小明""老板"。
   - 两个都可留空。点"开始使用"保存并进入主界面,或"添加模型供应商"保存后去配置 API。

保存逻辑:读取现有 UserProfile,把 assistantName(空则 null)和 userNickName(空则 null)合并写入,其他字段(age/city/mbti 等)保持不变。

引导页只在首次启动显示。修改称呼要去 设置 → 模型与服务 → 用户画像。

---

## 二、修改称呼与用户画像

入口:设置 → 模型与服务 → 用户画像(UserProfileEditPage)。

可编辑字段(对应 UserProfile 数据类,均可空):

- assistantName:助手名字(影响 AI 自称)。
- userNickName:用户称呼(影响 AI 对用户的称呼)。
- age:年龄。
- city:城市。
- mbti:MBTI 类型。
- occupation:职业。
- interests:兴趣爱好。

修改后立即保存到 DataStore,下次对话即生效(系统提示词每次构建都读最新值)。

注意:引导页填的称呼和这里的用户画像是同一份数据(UserProfile),引导页只是首次启动的快捷入口,后续修改都在这里。

---

## 三、称呼如何影响对话(双路径注入)

用户画像里的称呼通过两条独立路径影响 LLM,确保无论助手系统提示词怎么写,称呼都能生效:

### 路径 A:SystemPromptAssembler 的"用户画像" section

SystemPromptAssembler 组装系统提示词时,第 3 个 section 是"用户画像",从 SettingsRepository 读取 UserProfile,显式注入:

- "用户称呼: 请称呼用户为「X」"(userNickName 非空时)
- "你的名字: 你叫「Y」,在对话中以此自称"(assistantName 非空时)
- 以及 age / city / mbti / occupation / interests(非空时各自一行)

这是强约束,直接告诉 LLM 该怎么称呼用户、自己叫什么。

### 路径 B:TemplateTransformer 模板变量

TemplateTransformer 在系统提示词发送前做模板渲染,把 {{变量}} 占位符替换成实际值:

- {{user_name}} / {{user}} / {{nickname}} / {{userName}} → 用户称呼(userNickName,空时兜底为"你")
- {{char}} / {{assistant_name}} / {{character_name}} / {{assistantName}} → 助手名字(assistantName,空时不注入,渲染为空串)

这样助手在系统提示词里写"你是 {{char}},{{user_name}} 的灵感伙伴"时,会自动替换成真实称呼。

两条路径互补:路径 A 是显式指令(不管提示词怎么写都注入),路径 B 是模板替换(让提示词作者能用占位符灵活引用)。

---

## 四、默认助手系统提示词(三层人设)

新用户首次启动时,AssistantRepository.ensureDefaultExists() 会创建 id="default" 的默认助手,其 systemPrompt 是三层人设架构:

1. 身份(你是谁):"你叫 Muse。若用户在引导页给你起过名字,那个名字才是你真正的名字,以此自称。" 强调不是被动工具,而是主动愿意陪聊的存在。
2. 关系(你和用户是什么关系):"你和 {{user_name}} 是认识很久的人。感性与理性兼备,既有温度也有判断力。" 能分辨什么时候在闹着玩、什么时候真需要帮忙。
3. 说话方式:自然直接有温度,不客服腔,不强行建议,不用"总的来说""希望对你有帮助"收尾。闲聊短句接话,认真就先结论再步骤。

提示词里用了 {{user_name}} 占位符,由 TemplateTransformer 替换成用户称呼(路径 B)。同时 SystemPromptAssembler 的用户画像 section 也会显式注入"你叫「X」"(路径 A)。所以即使用户没填称呼(占位符兜底为"你"),显式注入也不会丢失。

注意:默认助手提示词只对新用户首次创建生效(INSERT OR IGNORE 策略,已有数据不覆盖)。老用户改过 systemPrompt 后不会被覆盖。

---

## 五、模板变量列表

TemplateTransformer 支持的内置变量(在系统提示词和消息模板里用 {{变量名}} 引用):

用户级(从 UserProfile / extras 读取):

- {{user_name}} / {{user}} / {{nickname}} / {{userName}}:用户称呼(userNickName,空时兜底"你")。
- {{char}} / {{assistant_name}} / {{character_name}} / {{assistantName}}:助手名字(assistantName)。

系统级(Transformer 内部计算,无需调用方传入):

- {{date}}:当前日期(yyyy-MM-dd)。
- {{time}}:当前时间(HH:mm:ss)。
- {{datetime}}:当前日期时间(yyyy-MM-dd HH:mm:ss)。
- {{locale}}:系统语言/地区(如 zh-CN、en-US)。
- {{timezone}}:系统时区(如 Asia/Shanghai)。
- {{device_info}}:设备型号与厂商(如 "Pixel 7 (Google)")。
- {{battery_level}}:电池电量百分比(0-100 整数,读取失败为 "unknown")。

会话级(由 ChatViewModel 注入):

- {{session_id}}:当前会话 id。
- {{model_id}}:当前模型 id。

自定义变量:extras 里 "template_vars" → Map<String, Any?> 会合并进变量映射。

模板引擎支持 Pebble 兼容语法:变量 {{ var }}、过滤器 {{ var | upper }}、条件 {% if %}、循环 {% for %}、注释 {# #}。语法错误时返回原文,不阻塞对话。

---

## 六、主题系统(3 层回退)

入口:设置 → 外观。

MuseTheme 组装 ColorScheme 时按三层优先级回退(从高到低):

1. dynamicColor(动态色):Android 12+ 跟随系统壁纸取色。默认关闭,需在设置里手动开启。开启后优先级最高,覆盖自定义和预设主题。
2. customTheme(自定义主题):用户基于种子色生成的 ColorScheme。如果当前 themeId 匹配某个自定义主题,用它。
3. presetTheme(预设主题):6 套手工调校的固定配色(warm_paper / sakura / ocean / spring / autumn / amoled)。默认回退路径。

也就是说:开了动态色就用系统壁纸色;没开动态色但选了自定义主题就用自定义;都没用就用预设主题。

主题模式(system / light / dark)单独控制明暗,与上述配色选择独立。

---

## 七、自定义主题

入口:设置 → 外观。

用户可创建自定义主题(CustomTheme):

- 选 1~3 个种子色:primaryColorArgb 必填,secondaryColorArgb / tertiaryColorArgb 可选(不填则由 primary 自动派生)。
- 算法:用 Material Color Utilities 的 HCT 色彩空间 + Variant.TONAL_SPOT 派生完整 ColorScheme(和 Android 12+ 壁纸动态色同款算法)。
- 用户只需选种子色,不用理解 ColorScheme 的 50+ 字段。
- 持久化为 JSON 存入 DataStore,新增/编辑/删除后立即重算配色。
- 生成结果同时支持浅色和深色模式。

预设主题与自定义主题的区别:预设是手工调校的固定 ColorScheme;自定义由算法生成,用户只选种子色。

---

## 八、字体大小

入口:设置 → 外观。

字号档位(fontSizeScale):small(小)/ medium(中,默认)/ large(大)/ xlarge(超大)。

通过 MuseTypography.scaled(fontSizeScale) 应用到所有文字,按档位缓存避免每次重组都 copy Typography。

---

## 九、注意事项

- 引导页(OnboardingScreen)只在首次启动且本地无 Provider 时全屏显示。重新触发入口:设置 → 关于 → 重新显示引导。
- 引导页填的称呼和 设置 → 模型与服务 → 用户画像 是同一份数据(UserProfile),引导页只是首次快捷入口,后续修改都在用户画像页。
- 称呼修改后立即生效(SystemPromptAssembler 每次 build 都读最新 UserProfile),不需要重启 app 或新建会话。
- 默认助手 systemPrompt 只对新用户首次创建生效,老用户改过的不会被覆盖。想恢复默认人设需手动复制默认提示词。
- 模板变量 {{user_name}} 在用户没填称呼时兜底为"你",不会在提示词里留下字面量占位符。{{char}} 在没填助手名字时不注入(渲染为空串)。
- 用户画像所有字段均可空,空字段不会注入到系统提示词(避免无意义内容)。
