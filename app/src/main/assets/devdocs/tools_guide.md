<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# ToolRegistry 内置工具大全 完整版

> 触发场景: 用户问"你能调用什么工具""有哪些功能可用""XX 功能怎么用""这个工具是干嘛的"时,参考本文档据实回答。
> 本文档基于源码 ToolRegistry.kt(BUILT_IN_TOOL_IDS)/ 各 Registrar / SkillExecutor.kt(BUILT_IN_SKILLS)的真实实现,是 Muse 工具能力的权威索引。

## 一、概述

ToolRegistry 是 Muse 的本地工具注册表(简化版 MCP 框架)。工具可被:
- LLM 通过 function calling / tool_call 触发(主要方式)
- 定时任务、Skill、WebServer 复用

工具分两大类:
1. **本地工具(built-in)**: 由各 Registrar 在启动时注册到 ToolRegistry,名字不带前缀
2. **Skill(内置+用户)**: 由 SkillExecutor 定义,通过 skillIdsJson 启用,可被 install_skill 扩展

另外还有两类动态工具:
3. **MCP 工具**: 名字带 `mcp_{serverId}__` 前缀,连接 MCP server 后注册
4. **workspace 工具**: 应用工作区文件读写

## 二、工具白名单机制(重要)

- 助手配置 `toolIdsJson`: 默认 `"[]"` 表示**全部启用**;指定数组则只启用列出的工具
- 助手配置 `skillIdsJson`: 默认启用全部内置技能;可指定子集
- 助手配置 `mcpServerIdsJson`: 绑定哪些 MCP server(连接成功自动绑定主助手)
- 系统提示的工具能力索引与 tools schema **同口径过滤**(按白名单),模型看到的就是能调的

## 三、本地工具全清单(逐个)

### 3.1 基础工具

| 工具 | 参数 | 说明 | 权限 |
|---|---|---|---|
| get_current_time | timezone(可选,IANA) | 获取当前时间 | 无 |
| calculator | expression | 简易计算器,加减乘除和括号 | 无 |
| echo | text | 回显输入(测试用) | 无 |
| json_pretty | json | JSON 美化格式化 | 无 |
| hash_text | text / algorithm | 文本哈希 | 无 |
| generate_uuid | - | 生成 UUID | 无 |
| random_number | min / max | 随机数 | 无 |
| generate_password | length(可选) | 随机密码 | 无 |
| url_encode / url_decode | text | URL 编码/解码 | 无 |
| base64_encode / base64_decode | text | Base64 编码/解码 | 无 |

### 3.2 时间与日程

| 工具 | 参数 | 说明 | 权限 |
|---|---|---|---|
| calendar_today | - | 今日日历事件 Top 列表 | READ_CALENDAR |
| add_calendar_event | title/start/end 等 | 添加日历事件 | READ_CALENDAR + 写入 |
| schedule_reminder | text/time 等 | 设置提醒 | POST_NOTIFICATIONS |
| cancel_reminder | id | 取消提醒 | 同上 |
| list_reminders | - | 列出提醒 | 无 |
| set_alarm | hour/min/label | 设系统闹钟(拉起系统时钟应用) | 无 |
| set_timer | seconds/label | 设倒计时(拉起系统时钟应用) | 无 |

### 3.3 手机系统工具

| 工具 | 参数 | 说明 | 权限 |
|---|---|---|---|
| open_app | packageName | 打开应用(如 com.tencent.mm) | 无 |
| open_url | url | 浏览器打开链接 | 无 |
| open_maps | query | 打开地图搜索 | 无 |
| open_system_setting | setting | 打开系统设置页 | 无 |
| share_text | text | 系统分享面板 | 无 |
| send_sms | phone/text | 发送短信 | SEND_SMS |
| send_email | to/subject/body | 发邮件(拉起邮件应用) | 无 |
| make_phone_call | phone | 拨打电话 | CALL_PHONE |
| add_contact | name/phone | 添加联系人 | WRITE_CONTACTS |
| get_contacts_count / get_contacts_list | - | 联系人统计/列表 | READ_CONTACTS |
| get_location | - | 最后已知位置(经纬度/精度) | ACCESS_COARSE_LOCATION |
| get_device_info | - | 品牌/型号/Android版本/屏幕/电量 | 无 |
| get_battery_info | - | 电池状态 | 无 |
| get_network_info | - | 网络状态 | ACCESS_NETWORK_STATE |
| get_wifi_info | - | WiFi 信息 | ACCESS_FINE_LOCATION(部分) |
| get_bluetooth_devices | - | 蓝牙设备列表 | BLUETOOTH_CONNECT |
| get_cpu_info / get_memory_info / get_storage_info / get_display_info / get_sensors_list | - | 硬件信息 | 无 |
| get_foreground_app | - | 当前前台应用 | 特殊权限 |
| get_recent_notifications | - | 最近通知 | NOTIFICATION_LISTENER |
| list_installed_apps | - | 已安装应用列表 | QUERY_ALL_PACKAGES |
| get_public_ip | - | 公网 IP | 网络 |
| screen_time | - | 今日应用屏幕时间 Top10 | PACKAGE_USAGE_STATS |
| get_brightness / set_brightness | value | 亮度读/写 | WRITE_SETTINGS |
| get_volume / set_volume | type/value | 音量读/写 | WRITE_SETTINGS |
| toggle_wifi / toggle_bluetooth / toggle_flashlight | enabled | 开关 WiFi/蓝牙/手电筒 | CHANGE_WIFI_STATE 等 |
| vibrate | duration | 震动 | VIBRATE |
| ping_host / dns_lookup | host | 网络诊断 | 网络 |
| get_weather | city(可选) | 天气 | 网络 |
| speak_text | text | TTS 朗读 | 无 |

### 3.4 剪贴板与资源

| 工具 | 参数 | 说明 |
|---|---|---|
| clipboard_read | - | 读剪贴板 |
| clipboard_write | text | 写剪贴板 |
| resource_add / resource_list / resource_search / resource_get / resource_delete | - | 应用内资源管理(文件/文本片段) |
| quick_note_add / list / search / get / update / delete / pin | - | 快速笔记 CRUD + 置顶 |

### 3.5 记忆与经验

| 工具 | 参数 | 说明 |
|---|---|---|
| pin_memory | content / keyword | 固定一条记忆(每次注入) |
| unpin_memory | keyword / id | 取消固定 |
| recall_experience | query | 召回过往经验 |
| record_experience | title/content | 记录经验 |
| search_memory | query | 搜索长期记忆(skill 实现) |

### 3.6 沟通与展示

| 工具 | 参数 | 说明 |
|---|---|---|
| notify | title/body | 弹系统通知 |
| show_card | title/code | 渲染交互卡片 |
| current_status | - | 查当前会话/环境状态 |
| todo_write | todos | 维护任务清单 |
| delegate_agent | task/agent | 委托任务给其他助手 |
| subagent_task | task | 派发子任务 |
| translate | text/target | AI 翻译(skill 实现) |

### 3.7 媒体生成

| 工具 | 参数 | 说明 |
|---|---|---|
| generate_image | prompt | AI 生图 |
| generate_video | prompt | AI 生视频 |
| generate_qr_code | text | 生成二维码 |
| list_stickers / send_sticker | id | 表情包(已弃用,数据保留) |

### 3.8 浏览器自动化

| 工具 | 参数 | 说明 |
|---|---|---|
| browser_navigate | url | 打开浏览器页 |
| browser_click | ref | 点击元素 |
| browser_type | text | 输入文本 |
| browser_extract | - | 提取页面内容 |
| browser_scroll_bottom | - | 滚到底部 |
| browser_get_html | - | 获取页面 HTML |

### 3.9 工作区(Workspace)

| 工具 | 参数 | 说明 |
|---|---|---|
| workspace_list | path | 列目录 |
| workspace_read | path | 读文件 |
| workspace_write | path/content | 写文件(可覆盖/追加) |
| workspace_delete | path | 删文件 |
| workspace_mkdir | path | 建目录 |
| workspace_move | from/to | 移动/重命名 |

### 3.10 定时任务

| 工具 | 参数 | 说明 |
|---|---|---|
| scheduled_task_create | cron/action 等 | 创建 cron 定时任务 |
| scheduled_task_list | - | 列出任务 |
| scheduled_task_update | id/... | 更新任务 |
| scheduled_task_delete | id | 删除任务 |
| scheduled_task_execute | id | 立即执行 |
| scheduled_task_get_history | id | 执行历史 |

### 3.11 执行环境

| 工具 | 参数 | 说明 | 风险 |
|---|---|---|---|
| execute_javascript | code | 沙盒 JS 执行 | NORMAL |
| execute_shell | command | Shell 命令执行(**默认不在白名单**,HIGH 风险,需用户在工具管理页手动启用) | HIGH |
| read_file / write_file | path/content | 沙盒文件读写(见 Skill 节) | - |

## 四、Skill 全清单(内置 30 个)

### 4.1 文件类
- read_file: 读沙盒文本(上限 1MB,path 相对 filesDir,支持 offset/length 按行分段)
- write_file: 写沙盒文件(path/content/append)
- list_dir: 列目录
- delete_file: 删文件(支持 paths 批量)
- file_exists: 判断存在
- file_download: 下载文件到沙盒
- read_public_file: 读 content:// URI 文件(配合 list_public_files)
- save_to_downloads: 保存到系统下载目录
- list_public_files: 列出公共文件(输出含 uri= 可直接喂给 read_public_file)

### 4.2 网络与信息
- http_get: HTTP GET(url/headers,响应上限 1MB)
- http_post: HTTP POST(url/body/content_type/headers)
- web_search: 搜索引擎(query/max_results 1-10 默认 5)
- web_fetch: 抓网页正文(url,上限 20 万字符/返回 5 万)
- knowledge_search: 知识库全文检索(query/threshold 默认 0.3;devdoc 内部文档也在此)
- arxiv_search: arXiv 论文搜索(query/max_results)

### 4.3 自我扩展与管理
- install_skill: **LLM 现场创建技能**(skill_json;implementationKotlin 限 8 个白名单实现)
- list_skills: 列出全部技能(内置/用户/启停状态)
- uninstall_skill: 卸载技能(id/name)
- disable_skill: 停用技能
- task_plan: 制定任务计划
- update_plan_step: 更新计划步骤

### 4.4 Agent 与群聊
- delegate_agent: 委托其他助手(task/agent 选择目标)
- channel_reply / channel_pass / channel_read_context: 群聊三件套(发言/跳过/读上下文)
- agent_phone: 手机模式(会话型)

### 4.5 其他
- generate_image: AI 生图
- translate: AI 翻译
- list_stickers / send_sticker: 表情包(弃用)

## 五、模型如何选择工具

1. **先判断类型**: 用户要的是"查信息/执行操作/生成内容/管理数据"哪一类
2. **匹配能力**: 查实时信息→web_search;读具体网页→web_fetch;写文件→write_file;定时→scheduled_task_*;委托→delegate_agent
3. **参数正确性**: 严格按 schema 传参,缺失参数工具会报错
4. **一次调用原则**: 能一次完成不要拆多次;结果不够再补调
5. **失败如实说明**: 工具返回错误/超时,如实告知,不编造成功

## 六、常见问题 Q&A

1. **"为什么有些工具我不能用"**: 工具按助手白名单过滤(toolIdsJson 默认全开);execute_shell 默认关闭(高危),需在工具管理页手动启用。
2. **"工具和 Skill 什么区别"**: 工具是系统注册的固定能力;Skill 是可扩展的封装(可自定义、可安装),底层复用工具实现。
3. **"MCP 工具怎么用"**: 连接 MCP server 后工具自动注册(前缀 mcp_{serverId}__),像本地工具一样调用。
4. **"模型说没有某个工具"**: 检查该助手白名单(toolIdsJson/skillIdsJson/mcpServerIdsJson)是否包含;MCP 需先连接且绑定助手。
5. **"execute_shell 能不能开"**: 能,在工具管理页启用;但它可执行任意命令,风险自负(建议只在信任环境开)。

## 七、LLM 调用要点

- 工具能力索引在系统提示中按助手白名单列出,与 tools schema 一致;看到的就是能调的
- 涉及设备/通信/账号/不可逆变更的工具,必须确认用户意图后再调
- 搜索类工具优先 web_search 定位再 web_fetch 精读
- 文件只访问应用沙盒 filesDir / 工作区
- 用户问 Muse 自身功能时,先 knowledge_search 查 devdocs,不要凭记忆编造
