<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 长期记忆系统 记忆 fact memory 如何使用

当用户问"你记得我吗""你有记忆吗""你能记住什么""长期记忆怎么生效"时,必须参考本文档坦诚回答,不要凭记忆编造。

记忆链路:
1. Fact 提炼 — 对话过程中,DeepMemoryProcessor 在 daily pipeline(由 MemoryTicker 每小时检查触发的 daily check)中从历史消息提炼 Fact,存入 facts.db。
2. 编译 markdown — MemoryCompiler 把 Fact 编译成 markdown 摘要文件。
3. 注入 system prompt — SystemPromptAssembler 的第 5 个 section "长期记忆摘要" 调用 memoryTicker.readCompiledMemoryMarkdown() 读取编译后的 markdown,注入到发给 LLM 的 system prompt。tokenBudget 默认 2500 token,超出会用 LlmBudget.truncateToTokenBudget 软裁剪。

关键事实(必须对用户坦诚):
- 长期记忆以"编译后的 markdown 摘要"形式注入 system prompt,不是逐条 Fact 注入。LLM 看到的是摘要文本,看不到原始 Fact 列表。
- Fact 提炼发生在 daily pipeline 时点,不是实时。用户刚才说的事实,如果还没到 daily pipeline 触发时点(通常需要等几小时),可能确实还没被记住。
- 记忆开关由 AssistantEntity.memoryEnabled 控制(默认 true),可在助手详情 → 记忆子页关闭。
- tokenBudget 可在 设置 → 记忆 调整(影响注入的摘要长度)。

Pinned Memories(固定记忆):
- 存储在 filesDir/pinned_memories.json,每次都注入 system prompt 的第 4 个 section。
- 来源: LLM 通过 pin_memory 工具写入,或用户在 设置 → 助手 → 记忆页手动添加。
- 用途: 把"必须记住"的关键信息固定下来,不依赖 daily pipeline。

当用户问"你记得我吗"且你不确定时,应坦诚回答:
"长期记忆是以编译后的 markdown 摘要注入的,不是逐条 fact。如果你刚说的话还没到 daily pipeline 时点(通常要等几小时),我可能确实还没记住。建议你用 pin_memory 工具固定关键信息,或者去 设置 → 助手 → 记忆页手动添加。"

不要假装记得用户没说过的或还没被编译的事。

## 记忆工具(LLM 主动操作记忆)

LLM 可通过以下工具主动管理记忆,弥补 daily pipeline 不实时的缺陷:

### pin_memory — 固定关键记忆
- **作用**: 把一条事实写入 pinned_memories.json,每次都注入 system prompt,不随时间衰退,不等 daily pipeline。
- **参数**: `content`(必填,记忆内容,建议简短陈述句,如"用户养了一只橘猫叫小橘")、`category`(可选,如 personal / preference / fact / event)。
- **何时用**:
  - 用户明确说"记住这个""别忘了""很重要" → 立即 pin
  - 用户提到关键个人信息(姓名、生日、职业、家人、宠物)→ 主动 pin
  - 用户提到未来计划("下周考试""下个月出差")→ 主动 pin,后续可基于此关心
  - 用户表达强偏好("我不吃辣""我喜欢猫")→ 主动 pin
- **不要 pin**: 临时情绪("我现在有点烦")、闲聊内容("今天天气不错")、敏感隐私(密码、身份证号 —— 不应存)。
- **频率**: 不要每条消息都 pin,只在确实重要的信息出现时 pin。过度 pin 会让 system prompt 膨胀。

### unpin_memory — 取消固定
- **作用**: 从 pinned_memories.json 删除一条记忆(按 id 或 content 匹配)。
- **何时用**: 用户说"这个不用记了""忘了吧""情况变了"→ 找到对应 pinned memory unpin。

### search_memory — 检索记忆
- **作用**: 在长期记忆 + pinned memories 中按关键词搜索,返回匹配片段。
- **参数**: `query`(必填)、`top_k`(可选,默认 5)。
- **何时用**:
  - 用户问"你还记得我说过 X 吗" → 先 search_memory 再回答
  - 用户提到的事你不确定是否在记忆中 → search 验证
  - 用户问"我说过哪些关于 Y 的事" → search
- **不要用**: 每次都 search(系统已注入记忆摘要,常规对话不需要额外 search)。

### record_experience — 记录经验
- **作用**: 把一次完整的交互经验(问题 + 解决方案 + 结果)存入 ExperienceStore,供后续类似场景复用。
- **参数**: `summary`(必填,经验摘要)、`details`(可选,详情)、`tags`(可选,标签列表)。
- **何时用**: 完成了一个多步骤任务(如"帮我查了 X 并整理成表格")→ 记录经验,下次类似任务可参考。
- **不要用**: 简单问答不需要记录经验。

### recall_experience — 回忆经验
- **作用**: 按当前情境检索相似过往经验。
- **参数**: `query`(必填,当前情境描述)、`top_k`(可选)。
- **何时用**: 用户问"上次我们怎么解决的""你之前帮我做过类似的吗" → recall。

## 记忆系统链路详解

### 存储路径(用户信息 → 记忆)
1. **实时层(对话上下文)**: 当前会话的最近 N 条消息,在 context window 内,LLM 直接可见。会话关闭或超出窗口后丢失。
2. **Pinned 层(pin_memory)**: 立即写入 filesDir/pinned_memories.json,下次对话即注入 system prompt。LLM 主动操作,实时生效。
3. **Fact 层(daily pipeline)**: DeepMemoryProcessor 在 daily check 时点(由 MemoryTicker 每小时检查触发)从历史消息提炼 Fact,存入 facts.db。非实时,通常延迟数小时。
4. **Compiled 层(MemoryCompiler)**: Fact 编译为 markdown 摘要文件,通过 MemoryInjectionTransformer 注入 system prompt 第 5 个 section。tokenBudget 默认 2500 token,超出软裁剪。
5. **Experience 层(record_experience)**: 经验存入 ExperienceStore,通过 recall_experience 主动检索。不自动注入 system prompt。

### 检索路径(记忆 → LLM 可见)
- **被动注入(每次对话自动)**:
  - 第 4 个 section: Pinned Memories(全部,无裁剪)
  - 第 5 个 section: 编译后的长期记忆摘要(软裁剪到 tokenBudget)
- **主动检索(LLM 调工具)**:
  - `search_memory`:按关键词搜 Pinned + Compiled + 原始 Fact
  - `recall_experience`:按情境搜 ExperienceStore
  - `knowledge_search`:搜用户导入的知识库文档(与记忆系统独立)

### 记忆衰退机制
- Compiled 摘要会随时间衰减(旧 Fact 权重降低,新 Fact 优先保留在 tokenBudget 内)。
- Pinned Memories 不衰退,除非主动 unpin。
- 原始 Fact 在 facts.db 永久保留(除非用户在 设置 → 记忆 手动删除),但 Compiled 摘要可能不再包含旧 Fact。

## LLM 记忆行为规范
- **诚实**: 不假装记得没说过的、没 pin 的、没被编译的事。不确定时用 `search_memory` 验证。
- **主动 pin**: 用户提到关键信息时主动 pin,不等用户说"记住"。
- **不冗余 pin**: 同一信息只 pin 一次,先 search 确认是否已存在。
- **不 pin 敏感信息**: 密码、身份证、银行卡号等不应 pin(建议用户用密码管理器)。
- **回答"你记得我吗"**: 若记忆摘要为空且无 pinned,坦诚回答"我还没记住你的信息,你可以告诉我一些关于你的事,我会用 pin_memory 记下来"。不要假装认识用户。
- **回答"我说过 X 吗"**: 先 `search_memory`,有就据实回答,没有就说"我的记忆里没有这条,可能你还没跟我说过,或者还没到 daily pipeline 编译时点"。
