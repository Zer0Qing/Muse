# Muse v2.6.4

> 修 RAG 关键路径无超时（最长卡 5 分钟）、分块重叠从未生效、索引中途失败静默丢数据、
> 主动消息 OOC、中转站多模态模型被误标。

---

## 中文

### 发消息可能卡 5 分钟（P0）

RAG 检索在发消息的关键路径上，而 query embedding **没有任何超时**：

- 复用的是聊天用的 OkHttpClient（`readTimeout = 300s`）
- embedding 端点一旦「接受连接但不响应」，用户点发送后最长要等 **5 分钟**才见到第一个字

现在给 query embedding 加了 **10 秒硬超时**。超时与失败同待遇：触发 5 分钟熔断，
本次检索降级到本地关键词搜索，聊天照常进行。

> 如果你的 embedding 服务一直正常，你大概没踩到这条；但换成响应慢的端点就会。

### 分块重叠从来没生效过（P1）

`markdownAware` 默认开启，而该分支里写反了顺序：

```kotlin
flush()                                                  // 先清空
val tail = current.toString().takeLast(overlap)          // 拿到空串
if (tail.isNotBlank()) current.append(tail)              // 永不执行
```

结果：**所有用户调的「分块重叠」都是死设置**——UI 可调、数据库存着，但从未用上。
已调换顺序，并补了 4 条单元测试锁住。

### 每块只注入前 200 字（P1）

知识库注入时硬编码 `take(200)`。你把 chunkSize 调到 500 时，**后 60% 永远进不了上下文**——
多花了 embedding 的钱，却只多看到 200 字。

现在上限提到 1200，真正的裁剪交给已有的 token 预算逻辑。

### 重建索引中途失败会静默清空（P0）

重建索引时先删旧块、再写新块。如果中途失败：

- 旧索引已经删了
- 新索引不完整
- 文档还在列表里，看起来正常
- **但检索永远召不到它，且没有任何提示**

现在会把它标记为「索引已丢失」，知识库列表里该文档下方显示红字：
**「索引已丢失（重建中途失败），检索不会命中。请长按重新索引。」**

不新增数据库列，无迁移风险。

### 主动消息 OOC（用户反馈）

症状：主动消息和上下文毫无关系、角色严重失真、英文角色用中文发消息。

根因是三处叠加：

**① 人设被截断到 500 字**
```kotlin
appendLine("你的人设参考:${assistant.systemPrompt.take(500)}")
```

**② 人格定义和思考链完全没读**——`personaDefinition` / `thinkingChain` 这两个字段
在主动消息里根本没被引用过。

**③ 没有任何语言约束**——prompt 全用中文书写，模型就跟着用中文回复，
英文角色也变成「美国佬说中文」。

现在：
- 完整人格定义 + 完整 systemPrompt（不再截断）
- 按角色自身语料推断发言语言，明确要求「即使提示词是中文，也别用中文发消息」
- 最近对话从 **5 条 → 12 条**，单条从 **200 字 → 400 字**（5 条形不成语境，正是「和上下文 0 关系」的原因）

### 中转站多模态模型仍然看不了图（用户反馈）

上版修了 `enhanceModel` 的覆盖逻辑，但漏了一处：

```kotlin
inputModalities = inputModalities ?: setOf("text")   // 建模型时就定成纯文本了
```

上游没声明能力时，模型一出生就带着「不支持视觉」，后面再聪明也救不回来。

已改为留空，交给能力注册表按 modelId 推断（`openrouter/gpt-4o` 这类带前缀的也能剥掉匹配）。
推断不出时行为与之前一致，不误伤。

### #8 第三方许可说明更正

`NOTICE` 和 `assets/licenses/manifest.json` 里，ML Kit 的许可证此前误标为 Apache-2.0。
实际 POM 声明是 **ML Kit Terms of Service**，已更正并注明。

Muse 本身没有任何 GPL 第 7 节附加许可或链接例外，这一点也在文件中写明。

---

## English

### Sending a message could hang for 5 minutes

RAG retrieval runs on the send path, and query embedding had **no timeout** —
it reused the chat OkHttpClient (`readTimeout = 300s`). An endpoint that accepts
the connection but never responds left users waiting up to 5 minutes for the
first token.

Added a **10-second hard timeout**; a timeout now triggers the same 5-minute
circuit breaker as a failure and falls back to local keyword search.

### Chunk overlap never worked

`markdownAware` defaults to on, and that branch cleared the buffer *before*
reading the tail:

```kotlin
flush()                                              // clears
val tail = current.toString().takeLast(overlap)      // always empty
if (tail.isNotBlank()) current.append(tail)          // never runs
```

Every user's overlap setting was silently dead. Fixed and covered by 4 unit tests.

### Only the first 200 characters were injected

Hard-coded `take(200)` meant raising chunkSize to 500 still only surfaced the
first 40% — more embedding cost, less context. Cap raised to 1200; actual
trimming is handled by the existing token budget.

### Failed re-index silently wiped documents

Re-index deletes old chunks before writing new ones. On mid-way failure, the old
index was gone, the new one incomplete, and the document still looked fine in the
list — but could never be retrieved, with no warning.

Now marked as broken, and the document card shows:
**"Index lost (re-index failed midway), retrieval will not hit it. Long-press to re-index."**
No schema change, no migration.

### Proactive messages out of character

Three compounding causes:

1. Persona truncated to 500 chars
2. `personaDefinition` and `thinkingChain` never read
3. No language constraint — the prompt is written in Chinese, so the model answers
   in Chinese even for English-speaking characters

Now: full persona, language inferred from the character's own text, and recent
context expanded from 5 to 12 messages (200 → 400 chars each).

### Relay-hosted multimodal models still could not see images

The previous release fixed the override logic but missed the constructor default
(`inputModalities ?: setOf("text")`). Now left empty so the capability registry
can infer it, including for prefixed ids like `openrouter/gpt-4o`.

### License notice correction (#8)

ML Kit was previously labeled Apache-2.0 in `NOTICE` and the licenses manifest.
The actual POM declares **ML Kit Terms of Service**; corrected and annotated.
Muse itself carries no GPL-7 additional permissions or linking exceptions.

---

## 安装

- 覆盖安装即可，**无需卸载**，数据保留
- 数据库版本仍是 **111**（本次未改 schema），**无迁移风险**
- 签名与 2.6.1 / 2.6.2 / 2.6.3 相同

## 已知事项

- #5（reasoning 泄漏到正文）仍未改，与「吐可以，空不行」的既有原则冲突，需单独定夺
- 主动消息的语言与语气改善依赖角色设定本身写得清楚；设定为空时仍会偏通用
