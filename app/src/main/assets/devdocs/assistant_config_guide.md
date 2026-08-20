<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 助手配置完整指南

> 触发场景: 用户问"怎么创建助手""助手有哪些设置""专属模型怎么配""工具白名单怎么设""怎么分享助手"时,参考本文档据实回答。
> 本文档基于源码 AssistantEntity.kt / AssistantRepository.kt / AssistantDetailPages.kt 的真实实现。

## 一、概述

Muse 的每个助手(Agent)是一个**独立人格**:拥有独立的 systemPrompt、模型、工具白名单、技能白名单、记忆配置、扩展(Lorebook/提示词注入/快捷消息)。多助手可协作(delegate_agent/群聊)。

- 入口: 设置 → 助手
- 默认存在主助手(id="default",名字"默认助手")
- 助手数量无上限,每个助手独立配置

## 二、创建/编辑/删除

1. 设置 → 助手 → 新建助手
2. 填写名称(必填),选择头像、模型
3. 编辑各子页配置(见下)
4. 删除: 助手列表长按 → 删除(不可恢复,注意确认)

## 三、助手详情 5 个子页详解

### 3.1 Basic(基础)
| 字段 | 说明 | 默认 |
|---|---|---|
| 头像 | 自定义头像 | 默认图标 |
| 名称 | 显示名 | - |
| modelId / providerId | **专属模型**: 指定后该助手固定用此模型,不受全局切换影响 | 空=用全局模型 |
| 助手卡片导出 | 导出为文件/二维码 | - |

### 3.2 Prompt(提示词)
| 字段 | 说明 |
|---|---|
| systemPrompt | 人格设定/行为规则(支持 {{var}} 模板变量) |
| messageTemplate | 消息模板(Pebble 兼容子集) |
| presetMessagesJson | 预设消息(开场白等) |

### 3.3 Extensions(扩展)
| 字段 | 说明 |
|---|---|
| toolIdsJson | 工具白名单(`[]`=全部;数组=只启用列出的) |
| skillIdsJson | 技能白名单(默认全部内置) |
| mcpServerIdsJson | 绑定 MCP server(连接成功自动绑定主助手) |
| Lorebook | 世界观设定,按关键词触发注入 |
| PromptInjection | 按条件注入额外提示词 |
| QuickMessage | 快捷消息 chips |

### 3.4 Memory(记忆)
| 字段 | 说明 | 默认 |
|---|---|---|
| memoryEnabled | 助手使用记忆 | true |
| useGlobalMemory | 用全局共享记忆(否则独立记忆) | true |
| enableRecentChatsReference | 注入近期会话参考 | false |
| enableTimeReminder | 时间相关记忆注入 | true |

### 3.5 Advanced(高级)
| 字段 | 说明 | 默认 |
|---|---|---|
| temperature | 采样温度 | 全局默认 |
| reasoningLevel | 思考等级 | AUTO |
| 请求头 | 自定义 HTTP 头(部分模型服务需要) | - |

## 四、专属模型与全局模型的关系(重点)

- 助手配置了 modelId(专属模型): 该助手**固定用专属模型**,会话里切换模型会被覆盖(切换时提示"已覆盖助手专属模型",切会话恢复)
- 助手未配置: 用全局默认模型(设置→模型与服务)
- 工具模型(toolModelId): 工具调用轮次用的轻量模型,per-assistant 可配

## 五、白名单语义速查

| 配置 | 空/默认 | 指定 |
|---|---|---|
| toolIdsJson | 全部工具 | 只启用列表中的 |
| skillIdsJson | 全部技能 | 只启用列表中的 |
| mcpServerIdsJson | 无 MCP | 绑定列表中的 server |
| memoryEnabled | true | false=不用记忆 |

## 六、分享与导入助手

- 导出: 助手详情 → 导出卡片 → 生成文件或二维码
- 导入: 扫码或导入文件 → 生成新助手(含 prompt/白名单/记忆配置)
- 分享给别人: 对方扫码即可获得同配置助手

## 七、常见问题 Q&A

1. **"怎么让每个助手用不同模型"**: 助手详情 → Basic → 选专属模型。
2. **"为什么切了模型没效果"**: 该助手配置了专属模型;在会话里手动切换会覆盖(提示),切会话恢复专属。
3. **"怎么让助手只用部分工具"**: Extensions → 工具白名单,取消不需要的。
4. **"新 MCP 助手用不了"**: 确认助手 Extensions → MCP 已绑定该 server(新连接的主助手会自动绑定)。
5. **"怎么备份助手配置"**: 导出卡片(文件/二维码),存好即可。
6. **"systemPrompt 里能用什么变量"**: {{user_name}}/{{char}}/{{date}} 等,Pebble 子集支持过滤器/循环/条件。

## 八、LLM 调用要点

- 助手是独立人格: 回复风格/行为以该助手 systemPrompt 为准
- 用户问"你是谁/你是哪个助手"时,按当前助手设定回答
- 不要混淆多个助手的记忆/身份(scope 隔离由系统保证)
- 用户提到"修改助手配置"时,引导到 设置 → 助手(工具层面不直接改配置,避免越权)
