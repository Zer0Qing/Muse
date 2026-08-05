# Muse 插件创作指导

本文档是 Muse 外部插件（`.muse-plugin`）的创作规范。助手在用户要求“写插件 / 创建插件 / 扩展功能”时，应阅读本文档并使用 `create_plugin` 工具。

## 1. 插件是什么

插件是一个 ZIP 包（扩展名 `.muse-plugin`），结构：

```text
my-plugin.muse-plugin
├── manifest.json   # 必需：插件清单
└── main.js         # 必需：JS 入口，导出工具函数
```

安装后插件工具会注册为 `plugin_<pluginId>_<toolName>`，由 SkillExecutor 路由到 JS 函数执行。插件放在 App 外部（`filesDir/plugins/<id>/`），不打包进 APK，用户可自行安装/卸载/启停。

## 2. manifest.json 格式

```json
{
  "id": "weather_tip",
  "name": "天气小贴士",
  "version": "0.1.0",
  "description": "根据城市返回一句天气建议",
  "author": "user",
  "entry": "main.js",
  "kind": "tool",
  "trust": "sandboxed",
  "capabilities": ["resource.read"],
  "permissions": [],
  "activationEvents": ["onStartup"],
  "enabled": true,
  "tools": [
    {
      "name": "get_weather_tip",
      "description": "返回指定城市的天气建议。参数 city 为城市名。",
      "parametersJson": "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"城市名\"}},\"required\":[\"city\"]}",
      "requiredJson": "[\"city\"]",
      "functionName": "getWeatherTip"
    }
  ]
}
```

字段说明：

- `id`：唯一标识，只允许小写字母、数字、下划线、连字符，必须声明至少一个 `tools`。
- `kind`：当前支持 `tool`。
- `trust`：`sandboxed`（推荐）或 `full-access`（谨慎）。
- `capabilities` 只允许：`resource.read`、`resource.write`、`network`、`ui`、`ui.mood`。
- `tools[].parametersJson` 是 OpenAI 兼容 JSON Schema；`tools[].functionName` 必须与 `main.js` 中的函数名一致。

## 3. main.js 写法

每个工具函数接收**一个参数对象**，返回字符串或 JSON 字符串。引擎以 `fn.apply(null, [args])` 方式调用，因此形参必须是单个对象：

```javascript
function getWeatherTip(args) {
  const city = args.city || "未知城市";
  return "今天" + city + "适合带伞，早晚温差较大。";
}
// 注意：不要写 export / module.exports，函数必须是全局可访问的
```

要点：

- 函数必须是全局可调用（不要写在模块作用域里用 `export`，当前执行器直接按函数名调用）。
- 只做纯逻辑；涉及文件/网络/系统能力的操作尽量交给内置工具，插件保持“计算 + 格式化”职责。
- 参数值都是字符串，需要数字时自行 `parseInt` / `Number`。
- 失败时返回以“错误：”开头的字符串，让模型能读懂原因。

## 4. 助手如何创建插件（create_plugin）

使用内置 `create_plugin` 工具，参数：

- `plugin_id`：插件 id（小写字母/数字/下划线/连字符）
- `name`：插件显示名
- `description`：插件/工具描述
- `tool_name`：LLM 调用用的工具名
- `function_name`：main.js 中的函数名
- `js_code`：main.js 完整内容（函数形参必须是一个参数对象，例如 function fn(args) { return ... }）
- 可选：`parameters_json`、`required_json`、`version`、`author`

工具会自动打包 manifest + main.js 并安装，返回安装结果。安装后用户可在“设置 → 插件”查看和管理。

## 5. 最佳实践

- 一个插件只做一个职责，工具描述要写清“何时调用、参数含义、返回内容”。
- 参数 schema 必须完整，避免模型漏参。
- 插件内容不要包含 API Key 等敏感信息。
- 先写小函数并用 `echo` / 简单测试验证，再安装正式版本。
- 更新插件时用同一 `plugin_id`，安装会覆盖旧版本。

## 6. 示例：待办提醒插件

```javascript
function summarizeTodos(args) {
  const text = args.text || "";
  const lines = text.split("\n").filter((l) => l.trim());
  return "共 " + lines.length + " 项待办。已完成：" +
    lines.filter((l) => l.includes("✓") || l.startsWith("[x]")).length +
    " 项。";
}
```

对应 manifest：

```json
{
  "id": "todo_summary",
  "name": "待办总结",
  "description": "把待办文本统计为完成情况摘要",
  "entry": "main.js",
  "kind": "tool",
  "trust": "sandboxed",
  "tools": [
    {
      "name": "summarize_todos",
      "description": "统计待办列表的完成情况，参数 text 为逐行待办文本",
      "parametersJson": "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}",
      "requiredJson": "[\"text\"]",
      "functionName": "summarizeTodos"
    }
  ]
}
```

调用 `create_plugin` 时，把上面的 `js_code` 与 manifest 字段分别传入即可。
