<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# 插件编写完整指南

> 触发场景: 用户问"插件怎么写""skillpkg 是什么""能做插件吗""插件和技能什么区别"时,参考本文档据实回答。
> 本文档基于源码 tools/script/ 模块(SkillPackageLoader/SkillPackageManifest/SkillEngine/WebViewSkillEngine)+ assets/skillpkg_templates/ 的真实实现。

## 一、概述

Muse 的插件(skillpkg)是**JS 技能包**: 一个包含 manifest.json + main.js 的目录,打包后加载进应用,提供新的工具能力。与 install_skill(Kotlin 白名单实现)不同,skillpkg 用 **JS 引擎执行**,能力更灵活(但仍受沙盒限制)。

## 二、skillpkg 包结构

```
my-plugin/
├── manifest.json   # 包描述(必填)
└── main.js         # 插件主逻辑(必填)
```

### manifest.json 字段全解
| 字段 | 必填 | 说明 |
|---|---|---|
| id | ✅ | 插件唯一 id(小写字母/数字/下划线/连字符) |
| name | ✅ | 显示名 |
| description | ✅ | 功能描述(给 LLM 看,决定何时调用) |
| version | 可选 | 版本号 |
| main | 可选 | 入口文件,默认 main.js |
| tools | 可选 | 暴露的工具定义(名称/参数/描述) |

### main.js 编写规范
- 使用 JS 引擎支持的 API(见 SkillEngine)
- 导出工具处理函数,参数从调用上下文获取
- 返回值(字符串/JSON)回填给 LLM

## 三、执行模型

| 引擎 | 说明 |
|---|---|
| SkillEngine | 标准 JS 沙盒执行(核心工具链) |
| WebViewSkillEngine | WebView 内执行(需要 WebView 环境的能力,如 DOM/网络) |

安全边界:
- JS 在沙盒内执行,不能直接访问应用内部 API
- 受限的能力通过注入的桥接接口提供
- 恶意代码无法绕过沙盒获取系统权限

## 四、加载/分发/更新

- 打包: skillpkg 目录 → zip
- 分发: 分享 zip 文件 / 从仓库安装
- 加载: 导入后解析 manifest → 注册工具到 ToolRegistry(带插件前缀)
- 更新: 重新导入覆盖(按 id)

## 五、插件与 install_skill 的区别

| 维度 | install_skill(Kotlin 白名单) | skillpkg(JS 插件) |
|---|---|---|
| 实现 | 复用 8 个内置 Kotlin 实现 | 自写 JS 逻辑 |
| 灵活性 | 低(仅组合白名单) | 高(可写复杂逻辑) |
| 安全 | 高(无任意代码) | 中(JS 沙盒) |
| 适用 | 简单工具(查/读/写/搜) | 复杂插件(流程/计算/多步) |
| 创建者 | 用户/LLM(对话创建) | 开发者(写代码打包) |

## 六、常见问题 Q&A

1. **"我不会写代码能做插件吗"**: 简单工具用对话让 AI 创建(install_skill)即可;复杂插件需要 JS 基础。
2. **"插件安全吗"**: JS 在沙盒执行,不能直接访问系统;但插件能发网络请求,导入不明来源插件需谨慎。
3. **"插件和技能哪个好"**: 简单场景用技能(install_skill 免代码);要自定义逻辑用插件。
4. **"怎么分享插件"**: 打包 zip 发给别人导入即可。
5. **"插件能用网络吗"**: 视沙盒桥接能力;HTTP 请求通常通过注入的 fetch/http 接口。

## 七、LLM 调用要点

- 用户要"简单的查/读/写工具" → 用 install_skill 现场创建(免代码)
- 用户要"复杂自定义插件" → 引导用户到插件开发文档/模板,说明需要 JS
- 插件注册的工具像普通工具一样调用,参数按 manifest 定义
- 插件执行失败/报错,如实反馈错误信息,不编造
