<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 插件编写完整指南

> 触发场景: 用户问"插件怎么写""能做插件吗""插件和技能什么区别"时,参考本文档据实回答。
> 本文档基于源码 data/plugin/ 模块(PluginPackageLoader/PluginManifest/PluginManager)+ tools/script/ 引擎的真实实现。

## 一、概述

Muse 的插件是 **JS 技能包**,文件格式为 `.muse-plugin`(ZIP): 一个包含 manifest.json + main.js 的目录,打包后加载进应用,提供新的工具能力。与 install_skill(Kotlin 白名单实现)不同,插件用 **JS 引擎执行**(WebViewSkillEngine),能力更灵活(但仍受沙盒限制)。

> `.skillpkg` 预览格式已下架,只保留 `.muse-plugin` 一种插件包格式。

## 二、.muse-plugin 包结构

```
my-plugin.muse-plugin (ZIP)
├── manifest.json   # 包描述(必填)
├── main.js         # 插件主逻辑(必填,kind=tool/provider 时)
└── assets/         # 可选,插件资源
```

### manifest.json 字段全解
| 字段 | 必填 | 说明 |
|---|---|---|
| id | ✅ | 插件唯一 id(小写字母/数字/下划线/连字符) |
| name | ✅ | 显示名 |
| description | ✅ | 功能描述(给 LLM 看,决定何时调用) |
| version | 可选 | 版本号,默认 0.1.0 |
| entry | 可选 | 入口 JS 文件,默认 main.js |
| kind | 可选 | 插件类型: tool(默认) / ui-skin / provider |
| trust | 可选 | 信任级别;外部 ZIP 必须为 sandboxed |
| capabilities | 可选 | 声明的能力(resource.read / resource.write / network / ui / ui.mood) |
| tools | 可选 | 暴露的工具定义(名称/参数/描述) |
| signature | 可选 | 发行者签名 envelope(公钥 + SHA256withECDSA 签名) |

### main.js 编写规范
- 使用 JS 引擎支持的 API(见 SkillEngine)
- 导出工具处理函数,参数从调用上下文获取
- 返回值(字符串/JSON)回填给 LLM
- 如需 IO 能力(HTTP 等),返回 `{ __bridge__: true, action: "...", params: {...} }` 桥接请求对象,由 Kotlin 侧审计后执行(SkillBridge),不破坏沙盒

## 三、执行模型

| 引擎 | 说明 |
|---|---|
| WebViewSkillEngine | WebView V8 沙盒执行(生产 JS 技能引擎,实现 SkillEngine 接口) |
| SkillBridge | `__bridge__` 桥接执行器: JS 返回桥接请求 → Kotlin 审计后执行安全实现 |

安全边界:
- JS 在沙盒内执行,禁用 fetch/XHR/WebSocket,不能直接访问应用内部 API
- 受限的能力通过桥接接口提供,所有 IO 经过 Kotlin 侧审计(SSRF 防护等)
- 外部包必须声明 `trust: "sandboxed"`;声明 full-access 的外部包在加载阶段拒绝

## 四、加载/分发/更新

- 打包: .muse-plugin 目录 → zip(扩展名 .muse-plugin)
- 分发: 分享 zip 文件 / 从插件市场安装
- 加载: PluginPackageLoader 解析 manifest → 校验安全约束(ZIP 防炸弹/路径遍历/入口存在)→ 注册工具到 ToolRegistry(带插件前缀)
- 信任: 发行者 P-256 ECDSA 签名 + 本机信任根 + 内置官方目录;未签名或不受信发行者的包不会自动受信
- 更新: 重新导入覆盖(按 id)

## 五、插件与 install_skill 的区别

| 维度 | install_skill(Kotlin 白名单) | 插件(.muse-plugin, JS) |
|---|---|---|
| 实现 | 复用 8 个内置 Kotlin 实现 | 自写 JS 逻辑 |
| 灵活性 | 低(仅组合白名单) | 高(可写复杂逻辑) |
| 安全 | 高(无任意代码) | 中(JS 沙盒 + 桥接审计) |
| 适用 | 简单工具(查/读/写/搜) | 复杂插件(流程/计算/多步) |
| 创建者 | 用户/LLM(对话创建) | 开发者(写代码打包) |

## 六、常见问题 Q&A

1. **"我不会写代码能做插件吗"**: 简单工具用对话让 AI 创建(install_skill)即可;复杂插件需要 JS 基础。
2. **"插件安全吗"**: JS 在沙盒执行,不能直接访问系统;网络请求走 Kotlin 桥接审计;导入不明来源插件需谨慎。
3. **"插件和技能哪个好"**: 简单场景用技能(install_skill 免代码);要自定义逻辑用插件。
4. **"怎么分享插件"**: 打包成 .muse-plugin zip 发给别人导入即可。
5. **"插件能用网络吗"**: 通过 __bridge__ 桥接;HTTP 请求经 Kotlin 侧 SSRF 防护后执行。

## 七、LLM 调用要点

- 用户要"简单的查/读/写工具" → 用 install_skill 现场创建(免代码)
- 用户要"复杂自定义插件" → 引导用户说明需要 JS,可参考插件开发模板
- 插件注册的工具像普通工具一样调用,参数按 manifest 定义
- 插件执行失败/报错,如实反馈错误信息,不编造
