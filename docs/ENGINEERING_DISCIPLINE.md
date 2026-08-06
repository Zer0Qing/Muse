# Muse 工程纪律指南

> 本文档详细说明 Muse 项目的工程纪律规范，是 [AGENTS.md](../AGENTS.md) 的扩展说明。
> 所有贡献者（包括 AI Agent）必须遵守这些规范，CI 会自动检查违规模式。

## 目录

1. [核心原则](#1-核心原则)
2. [兜底逻辑分类](#2-兜底逻辑分类)
3. [类型安全实践](#3-类型安全实践)
4. [异常处理规范](#4-异常处理规范)
5. [CI 检查机制](#5-ci-检查机制)
6. [必要容错清单](#6-必要容错清单)
7. [代码审查检查清单](#7-代码审查检查清单)

---

## 1. 核心原则

### 1.1 明确优于隐式

代码应该**明确处理所有边界情况**，而不是用兜底逻辑掩盖问题。

```kotlin
// ❌ 坏味道：掩盖了真正的错误
val result = try { doSomething() } catch (e: Exception) { null }

// ✅ 正确：明确处理错误
val result = resultOf { doSomething() }
    .onSuccess { /* 处理成功 */ }
    .onError { Logger.e(TAG, "doSomething 失败", it) }
```

### 1.2 单一事实源

- 文档和代码必须同步更新
- 新增公共 API 必须添加 KDoc 注释
- 修改行为必须更新相关文档
- 新增配置项必须更新配置文档

### 1.3 Don't Break Userspace

- 公共 API 的签名变更必须保持向后兼容
- 内部实现更换（如 Provider 切换）不算 breaking change
- 数据库迁移必须向前兼容（不能跳过版本）

---

## 2. 兜底逻辑分类

### 2.1 必要容错（允许，需注释）

| 场景 | 示例 | 说明 |
|------|------|------|
| 用户输入容错 | `userInput.trim().ifEmpty { "默认助手" }` | 用户可能输入空白 |
| 网络请求重试 | 带重试次数上限的 retry | 必须有上限，避免无限重试 |
| 流式响应中断恢复 | stream-guard 兜底 | 商汤模型发送 `finishReason=""` 作为 false finish |
| 数据库迁移兼容 | 旧版本字段缺失时降级 | 必须注释说明迁移版本 |
| JSON 解析降级 | `runCatching { decode }.getOrDefault(emptyList())` | 外部数据格式不可控 |
| 资源清理 | `runCatching { stream.close() }` | 清理操作失败不应影响主流程 |

### 2.2 坏味道（禁止）

| 场景 | 示例 | 问题 |
|------|------|------|
| 掩盖类型错误 | `val data: Any = parseResponse()` | 丢失类型信息 |
| 忽略异常不记日志 | `catch (e: Exception) {}` | 问题被隐藏，难以排查 |
| 无条件返回 null | `?: null` 无注释 | 调用方无法区分"无数据"和"出错" |
| 联合类型兜底 | `else -> null` 在 when 中 | 类型不安全 |
| 无限重试 | `while(true) { try { ... } catch { } }` | 可能永远不退出 |

---

## 3. 类型安全实践

### 3.1 禁止回退成 Any/unknown

```kotlin
// ❌ 禁止
val data: Any = parseResponse()
val name = data as String  // 不安全转换

// ✅ 正确
val data: JsonObject = parseResponse()
val name = data["name"]?.jsonPrimitive?.contentOrNull
    ?: error("Response missing 'name' field")
```

### 3.2 使用 sealed class 替代联合类型

```kotlin
// ❌ 禁止
fun getData(): Any = when (type) {
    "string" -> "text"
    "number" -> 42
    else -> null  // 联合类型兜底
}

// ✅ 正确
sealed class Data {
    data class Str(val value: String) : Data()
    data class Num(val value: Int) : Data()
    object Empty : Data()
}

fun getData(): Data = when (type) {
    "string" -> Data.Str("text")
    "number" -> Data.Num(42)
    else -> error("Unknown type: $type")
}
```

### 3.3 可空类型处理

```kotlin
// ❌ 禁止：直接 as String
val value: String? = getValue()
val processed = (value as String).trim()

// ✅ 正确：用 as? + ?:
val processed = value?.trim() ?: ""

// ✅ 正确：用 if-let 模式
if (value != null) {
    process(value.trim())
}
```

### 3.4 非空断言 !!

```kotlin
// ❌ 禁止：无注释的 !! 使用
val name = user!!.name

// ✅ 允许：有明确前提的 !! (但优先使用 require/check)
require(user != null) { "User must not be null at this point" }
val name = user.name

// ✅ 允许：同行有注释说明
val name = user!!.name // 已在 init 块确认非空
```

---

## 4. 异常处理规范

### 4.1 suspend 函数中的 runCatching

`runCatching` 会吞掉 `CancellationException`，在 suspend 函数中**块内调用 suspend 函数**时禁止使用。

```kotlin
// ❌ 危险：在 suspend 函数内用 runCatching 包裹 suspend 调用
suspend fun loadData() {
    runCatching { repository.fetchData() }  // fetchData 是 suspend
        .getOrNull()  // 会吞掉 CancellationException
}

// ✅ 正确：使用 resultOf (项目封装，自动重抛 CancellationException)
suspend fun loadData() {
    resultOf { repository.fetchData() }
        .onSuccess { /* ... */ }
        .onError { Logger.e(TAG, "加载失败", it) }
}
```

### 4.2 纯同步逻辑的 runCatching

纯同步逻辑（JSON 解析、枚举 valueOf、日期解析）使用 `runCatching` 是安全的，但需补 `.onFailure { Logger.w }`。

```kotlin
// ✅ 允许：纯同步逻辑 + 日志
val date = runCatching { LocalDate.parse(input) }
    .onFailure { Logger.w(TAG, "日期解析失败: $input") }
    .getOrNull()
```

### 4.3 空 catch 块

```kotlin
// ❌ 禁止：error 级别，阻止合并
try { riskyOperation() } catch (e: Exception) { }

// ✅ 正确：记录日志
try { riskyOperation() } catch (e: Exception) {
    Logger.e(TAG, "riskyOperation 失败", e)
}

// ✅ 正确：资源清理可忽略失败，但需注释
runCatching { stream.close() } // 清理操作，忽略失败
```

---

## 5. CI 检查机制

### 5.1 检查流程

```
PR 提交
  ↓
scope-classifier (分类变更文件)
  ↓
quick-checks (始终运行)
  ├── check_repo_hygiene.py (仓库卫生)
  └── check_engineering_discipline.py (工程纪律)
  ↓
[按作用域选择性运行]
  ├── localization-check (本地化校验)
  ├── script-check (CI 脚本测试)
  ├── check (Kotlin 编译 + 单测)
  └── assemble (完整 APK 构建)
```

### 5.2 工程纪律检查规则

| 规则名 | 严重度 | 说明 |
|--------|--------|------|
| `unsafe_cast` | warning | `as String/Int/Any` 等不安全转换 |
| `non_null_assertion` | warning | 无注释的 `!!` |
| `empty_catch` | **error** | 空 catch 块（阻止合并） |
| `null_fallback` | warning | 无注释的 `?: null` |
| `default_fallback` | warning | `?: "unknown"/"default"` |
| `any_type_declaration` | warning | `val/var x: Any` 类型回退 |
| `any_return_type` | warning | `fun foo(): Any` 返回 Any |
| `when_else_null` | warning | `else -> null` 联合类型兜底 |
| `nullable_unsafe_cast` | warning | `(value as String)` 可空值不安全转换 |
| `todo_overflow` | warning | TODO/FIXME 超过 10 个 |

### 5.3 注释豁免

以下规则在同行有 `//` 注释时豁免：
- `non_null_assertion`：`!!` 后同行有注释
- `null_fallback`：`?: null` 后同行有注释
- `when_else_null`：`else -> null` 后同行有注释

泛型参数中的 `Any` 豁免（如 `Map<String, Any>`）。

### 5.4 本地运行

```bash
# 全量扫描
py -3 ci/script/check_engineering_discipline.py

# 只扫描 diff
py -3 ci/script/check_engineering_discipline.py --diff --base origin/main

# 运行测试
py -3 ci/test/test_engineering_discipline.py
```

---

## 6. 必要容错清单

以下场景已确认为**必要容错**，CI 允许但必须保留注释说明：

### 6.1 stream-guard（流式响应恢复）

```kotlin
// ✅ stream-guard 兜底，有明确业务原因
// 商汤模型发送 finishReason="" 作为 false finish 信号，必须兜底
val isFinished = finishReason == "stop" || finishReason == "length"
```

### 6.2 MemoryLlmClient（推理模型降级）

```kotlin
// ✅ reasoning 模型把所有输出放在 reasoning_content
val effectiveText = text.ifEmpty { reasoningContent } // reasoning fallback
```

### 6.3 数据库迁移

```kotlin
// ✅ 旧版本数据兼容
val legacyConfig = runCatching { decode(oldJson) }
    .getOrElse { defaultConfig } // 兼容 v1.0.48 之前的格式
```

### 6.4 资源清理

```kotlin
// ✅ 资源释放失败不应影响主流程
runCatching { cursor.close() } // 清理操作，忽略失败
runCatching { stream.close() } // 清理操作，忽略失败
```

### 6.5 外部数据解析

```kotlin
// ✅ 外部 JSON 格式不可控
val tags = runCatching { AppJson.decodeFromString<List<String>>(raw) }
    .getOrDefault(emptyList()) // 外部数据，格式不可控
```

---

## 7. 代码审查检查清单

提交 PR 前请确认：

- [ ] 无 `as String/Int/Any` 不安全转换（改用 `as? + ?:`）
- [ ] 无无注释的 `!!`（改用 `require/check` 或加注释）
- [ ] 无空 catch 块（补日志或处理异常）
- [ ] 无无注释的 `?: null`（加注释说明原因）
- [ ] 无 `val x: Any` 类型回退（改用具体类型或 sealed class）
- [ ] 无 `fun foo(): Any` 返回 Any（改用具体类型或 sealed class）
- [ ] 无 `else -> null` 联合类型兜底（改用 `error()` 或 sealed class）
- [ ] suspend 函数内的 `runCatching` 已改为 `resultOf`（防 CancellationException）
- [ ] 纯同步 `runCatching` 已补 `.onFailure { Logger.w }`
- [ ] 新增公共 API 已添加 KDoc 注释
- [ ] 行为变更已更新相关文档
- [ ] 数据库 schema 变更已编写 Migration
- [ ] TODO/FIXME 数量未超限（单文件 ≤ 10）

---

*本文件是活文档，随项目演进持续更新。*