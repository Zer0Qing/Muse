<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 多 Agent 协作(delegate_agent / 团队 / 自动路由)

## 功能说明
muse 支持多 Agent 协作:
- **delegate_agent 工具**:主助手把任务委托给指定子助手执行。
- **Agent 能力标签**:每个助手可标注能力标签(如 code / writing / research),供自动路由匹配。
- **团队工作流**:用户可把多个助手编为团队,支持串行、并行、条件分支编排。
- **结果聚合**:多 Agent 结果可通过合并、投票、专家评审等策略统一输出。

## 使用场景
- 主助手是通用对话助手,遇到专业任务自动路由给专业子助手
- 写作助手 + 审稿助手 + 翻译助手协作完成一篇文章
- 研究员助手收集资料 + 写手助手整理成文
- 多 Agent 并行投票或交叉验证同一问题

## delegate_agent 工具调用

### 基本调用
```json
{
  "name": "delegate_agent",
  "arguments": {
    "assistantId": "writer",
    "task": "把以下要点写成一段话: ...",
    "context": "可选的上下文"
  }
}
```

### 结构化调用(v1.200+)
```json
{
  "name": "delegate_agent",
  "arguments": {
    "assistantId": "writer",
    "task": "把以下要点写成一段话: ...",
    "context": "可选的上下文",
    "request_id": "req-123",
    "attachments": ["artifact-1", "file:///path/to/file.txt"],
    "timeout": 120,
    "response_format": "json"
  }
}
```

### 参数说明
- `assistantId`(必填):子助手 id,可在助手管理页查看。
- `task`(必填):要委托的任务描述,自然语言。
- `context`(可选):补充上下文。
- `request_id`(可选):请求唯一 id,用于链路追踪。
- `attachments`(可选):附件/产物列表,可传 JSON 数组或逗号/换行分隔。
- `timeout`(可选):超时秒数,默认 60,最大 600。
- `response_format`(可选):返回格式,可选 `text`(默认) / `json` / `markdown` / `code`。

## Agent 能力标签与自动路由

- 每个 Assistant 实体有 `capabilitiesJson` 字段,存储能力标签数组。
- 常见标签:chat / reasoning / writing / creative / code / math / research / data / legal / medical / finance / education / image / video / web_search / memory / schedule / knowledge / translation / review。
- 当用户未指定子助手时,`AgentRouter` 会根据任务文本中的关键词匹配能力标签,推荐最合适的助手或团队。
- 路由结果包含置信度与原因,低置信度时返回候选供主助手决策。

## 团队工作流

### 团队配置
- 用户在"设置 → 多 Agent"中创建团队,指定成员 assistantId 列表。
- 团队可额外配置工作流(`workflow`),定义节点、依赖与执行模式。

### 工作流节点模式
- **SEQUENTIAL(串行)**:按顺序执行,前序结果作为后序上下文。
- **PARALLEL(并行)**:无依赖节点并发执行,结果按聚合策略合并。
- **CONDITIONAL(条件)**:根据前置结果决定是否执行或如何执行。

### 结果聚合策略
- **MERGE**:拼接所有子结果,保留来源。
- **VOTE**:对短答案进行多数投票。
- **EXPERT_REVIEW**:选择最长/最详尽结果。
- **FIRST_SUCCESS**:返回第一个成功结果。
- **LLM_REVIEW**(v1.201):由 `LlmAggregator` 调用独立评审模型,综合所有候选结果输出融合文本;`LlmAggregator` 为 null 时降级为 EXPERT_REVIEW。

## v1.201 新增能力

### Agent 特定记忆
- `FactEntity` 新增 `scope` 字段(默认 "main"),用于区分主助手与各子助手的记忆作用域。
- 记忆页 (`MemoryScreen`) 新增作用域筛选 chip 行,可按 Agent 切换查看。
- `MemoryInjectionTransformer` 在注入记忆时按当前会话绑定的 assistantId 过滤,避免子助手读到主助手的私有事实。
- 数据库迁移 v7→v8:`ALTER TABLE facts ADD COLUMN scope` + 索引。

### 委派链路可视化
- `DelegationChainTracker` 维护当前会话内所有委派请求的链路状态(StateFlow),UI 实时展示。
- `DelegationChainCard` 在最后一条 AI 消息下方渲染链路树:节点状态(RUNNING / COMPLETED / FAILED / TIMEOUT / CANCELLED / PAUSED)、耗时、结果预览。
- 点击节点可弹出 `NodeDetailDialog` 查看完整任务、结果、错误信息。

### 人机协作暂停点
- `DelegationPauseManager` 管理暂停/恢复/取消状态,基于 `CompletableDeferred` 实现协程挂起等待用户决策。
- 暂停点配置(`PausePolicy`):
  - `pauseBeforeTeam`:团队工作流执行前
  - `pauseBeforeEachMember`:团队每个成员执行前
  - `pauseOnIntermediateResult`:中间结果产出后
  - `pauseOnHighRisk`:高风险任务前(默认开启)
  - `autoTimeoutSec`:暂停等待超时(秒),超时自动拒绝
- 用户决策类型:APPROVE(批准)/ MODIFY(修改后继续)/ REJECT(拒绝)/ CANCEL(取消整个委派)。
- `DelegationConfirmDialog` 在 ChatScreen 中渲染暂停请求卡片,支持输入修改后的任务文本。
- `DelegationRequest.pausePoints` 可显式声明暂停点,优先级高于 PausePolicy。

### LLM 综合评审聚合
- `LlmAggregator` 接收候选结果列表,构造评审 prompt 调用独立 LLM,返回融合后的文本。
- 用于团队工作流 `LLM_REVIEW` 聚合策略,也用于多 Agent 交叉验证场景。
- 超时/失败时返回 null,由上游降级为 EXPERT_REVIEW。

## 子助手执行流程
1. 根据 `assistantId` 取子助手配置(含 systemPrompt / modelId / temperature / maxTokens / capabilitiesJson)。
2. 构造消息:systemPrompt + 携带的上下文消息 + 任务与附件。
3. 调用 LLM 跑一轮(非流式,独立调用)。
4. 返回结构化的 `DelegationResult`,包含 resultText / durationMs / assistantId / error 等。

## 重要约束
- 子助手不会再调 delegate_agent(递归深度上限 3,防御性)。
- 子助手独立调用 LLM,不走主对话的流式回环。
- 失败时返回错误信息,主助手能看到并决定是否重试。
- delegate_agent 为高风险工具,默认需要用户审批(可在工具权限设置中调整)。

## 用户如何配置多 Agent
1. 在助手管理页创建多个助手,并为每个助手设置能力标签。
2. 在"设置 → 多 Agent"中创建团队(可选),配置成员与工作流。
3. 在"设置 → Skill"页确认 `delegate_agent` 已启用(默认启用)。
4. 在对话中直接描述任务,muse 会自动路由到合适的助手或团队。

## 触发建议
LLM 应该在以下情况主动调 delegate_agent:
- 用户明确说"委托给 X 助手" / "让 X 助手做"。
- 任务超出主助手能力范围(如主助手是通用,任务需要专业知识)。
- 用户提到多助手协作场景或团队名称。
- 需要多 Agent 投票/交叉验证。

不应该调的情况:
- 简单闲聊/问答。
- 主助手自己能完成的任务。
- 没有合适的子助手(此时应坦白告知用户)。
