# AGENTS.md — Muse 工程纪律

> 本文档定义 Muse 项目的工程纪律规范，所有贡献者（包括 AI Agent）必须遵守。
> CI 会自动检查违反规范的代码模式，违反将阻止合并。

## 1. 严禁回退代码和兜底逻辑

**原则：** 代码应该明确处理所有边界情况，而不是用兜底逻辑掩盖问题。

### 1.1 禁止的模式

```kotlin
// ❌ 禁止：无条件兜底，掩盖了真正的错误
val result = try { doSomething() } catch (e: Exception) { null }
val name = user?.name ?: "unknown"  // 无注释说明为什么 name 可能为 null

// ❌ 禁止：回退到默认值而不记录日志
val timeout = config?.timeout ?: 30_000

// ❌ 禁止：空 catch 块
try { riskyOperation() } catch (e: Exception) { }
```

### 1.2 允许的例外

业务必要的容错必须**添加注释说明原因**：

```kotlin
// ✅ 允许：stream-guard 兜底，有明确的业务原因
// 商汤模型发送 finishReason="" 作为 false finish 信号，必须兜底
val isFinished = finishReason == "stop" || finishReason == "length"

// ✅ 允许：用户输入容错
val displayName = userInput.trim().ifEmpty { "默认助手" } // 用户可能输入空白
```

### 1.3 必要容错 vs 坏味道

| 场景 | 判定 |
|------|------|
| 用户输入容错 | ✅ 必要 |
| 网络请求重试 | ✅ 必要（但需有重试次数上限） |
| 流式响应中断恢复 | ✅ 必要（stream-guard） |
| 数据库迁移兼容 | ✅ 必要 |
| 掩盖类型错误 | ❌ 坏味道 |
| 忽略异常不记日志 | ❌ 坏味道 |
| 无条件返回 null/默认值 | ❌ 坏味道 |

## 2. 类型安全

### 2.1 禁止回退成 any/unknown

```kotlin
// ❌ 禁止
val data: Any = parseResponse()
val name = data as String  // 不安全转换

// ✅ 正确
val data: JsonObject = parseResponse()
val name = data["name"]?.jsonPrimitive?.contentOrNull ?: error("Response missing 'name' field")
```

### 2.2 可空类型处理

```kotlin
// ❌ 禁止：直接 as String
val value: String? = getValue()
val processed = (value as String).trim()

// ✅ 正确：用 if 或 ?:
val value: String? = getValue()
val processed = value?.trim() ?: ""

// ✅ 正确：用 if-let 模式
val value: String? = getValue()
if (value != null) {
    process(value.trim())
}
```

### 2.3 非空断言 `!!`

```kotlin
// ❌ 禁止：无注释的 !! 使用
val name = user!!.name

// ✅ 允许：有明确前提的 !! (但优先使用 require/check)
require(user != null) { "User must not be null at this point" }
val name = user.name
```

## 3. 代码更改必须维护文档

- 新增公共 API 必须添加 KDoc 注释
- 修改行为必须更新相关文档
- 新增配置项必须更新配置文档

## 4. 禁用 PowerShell 编辑代码文件

PowerShell 的编码处理可能导致 UTF-8 文件损坏（特别是中文注释）。

```powershell
# ❌ 禁止：用 PowerShell 命令编辑代码
Set-Content -Path "Foo.kt" -Value "new content"
[System.IO.File]::WriteAllText("Foo.kt", "content")  # 可能写入 BOM

# ✅ 正确：使用专用编辑工具
# Edit / Write 工具
```

## 5. 禁止回退兜底成 unknown/any/联合类型

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
}
fun getData(): Data = when (type) {
    "string" -> Data.Str("text")
    "number" -> Data.Num(42)
    else -> error("Unknown type: $type")
}
```

## 6. Don't Break Userspace

- 公共 API 的签名变更必须保持向后兼容
- 内部实现更换（如 Provider 切换）不算 breaking change
- 数据库迁移必须向前兼容（不能跳过版本）

## 7. CI 检查规则

`ci/script/check_engineering_discipline.py` 会自动扫描：

| 检查项 | 规则名 | 正则模式 | 严重度 |
|--------|--------|----------|--------|
| 不安全的类型转换 | `unsafe_cast` | `\bas\s+(String\|Int\|Long\|Float\|Double\|Boolean\|Any\|JsonObject)\b` | warning |
| 无注释的 `!!` | `non_null_assertion` | `!!(?![^\n]*//)` | warning |
| 空 catch 块 | `empty_catch` | `catch\s*\([^)]+\)\s*\{\s*\}` | error |
| 无条件 null 兜底 | `null_fallback` | `\?:\s*null(?!\s*//)` | warning |
| 无条件默认值 | `default_fallback` | `\?:\s*"(?:unknown\|default)"` | warning |
| 变量类型回退成 Any | `any_type_declaration` | `\b(?:val\|var)\s+\w+\s*:\s*Any\b(?!\s*[<,])` | warning |
| 函数返回 Any 类型 | `any_return_type` | `\bfun\s+\w+\s*\([^)]*\)\s*:\s*Any\b(?!\s*[<,])` | warning |
| when 联合类型兜底 | `when_else_null` | `\belse\s*->\s*null\b(?!\s*//)` | warning |
| 可空值不安全转换 | `nullable_unsafe_cast` | `\(\s*\w+\s+as\s+(String\|Int\|Long\|...)\s*\)` | warning |
| TODO/FIXME 超限 | `todo_overflow` | `TODO\|FIXME` | warning (>10) |

**注释豁免规则：**
- `!!` 后同一行有 `//` 注释 → 豁免
- `?: null` 后同一行有 `//` 注释 → 豁免
- `else -> null` 后同一行有 `//` 注释 → 豁免
- `Map<String, Any>` 等泛型参数中的 `Any` → 豁免（合法用法）

## 8. 违规处理

- **error**: 阻止合并，必须修复
- **warning**: 需要审查，可在后续 PR 修复

---

*本文件是活文档，随项目演进持续更新。*
