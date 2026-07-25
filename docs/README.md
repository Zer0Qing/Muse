<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="../app/src/main/res/drawable/ic_muse_logo.png">
    <img src="../app/src/main/res/drawable/ic_muse_logo.png" width="96" height="96" alt="Muse">
  </picture>
</p>

<h1 align="center">Muse</h1>

<p align="center">
  <b>记得你的 AI 伙伴</b><br>
  <i>四层记忆 · 多模型 · 离线优先 · 可扩展</i>
</p>

<p align="center">
  <a href="README_EN.md">English</a> · <b>中文</b>
</p>

<p align="center">
  <a href="../LICENSE"><img src="https://img.shields.io/badge/license-GPLv3-blue.svg" alt="License: GPL v3"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen" alt="Min SDK">
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple" alt="Kotlin">
  <img src="https://img.shields.io/badge/Compose-Material%203-ff69b4" alt="Compose">
  <img src="https://img.shields.io/badge/build-passing-success" alt="Build">
  <img src="https://img.shields.io/badge/version-1.0.0-blue" alt="Version">
</p>

<p align="center">
  <a href="#-为什么选择-muse">为什么选择 Muse</a> ·
  <a href="#-功能特性">功能特性</a> ·
  <a href="#-快速开始">快速开始</a> ·
  <a href="#-架构设计">架构设计</a> ·
  <a href="#-技术栈">技术栈</a> ·
  <a href="#-隐私安全">隐私安全</a> ·
  <a href="#-开源协议">开源协议</a>
</p>

---

## 💡 为什么选择 Muse？

大多数 AI 应用把每次对话都当成初次见面——没有记忆、没有连续性、没有个性。**Muse 不一样。**

它通过四层记忆系统与你建立持久关系，记住你的偏好、历史和重要事项。支持多种模型（OpenAI、Anthropic、Gemini、DeepSeek 或任何 OpenAI 兼容服务），能语音输入、联网搜索、执行工具，甚至在你许久未联系时主动发起对话。

**默认离线运行，无需账号，除非主动开启，否则数据不会离开你的设备。**

---

## ✨ 功能特性

### 核心能力

<details open>
<summary><b>🧠 四层记忆系统</b> — 记住重要的事，自然遗忘不重要的事</summary>

```
对话 → 事实提取 → 滚动摘要
    → 编译聚合 → 深度处理
```

- **关键事实永不衰减**：医疗信息、财务细节、核心身份等重要度 ≥ 2 的事实不会被自动遗忘
- **日常细节自然淡化**：临时偏好和随口 remarks 会随时间自然衰减
- **每条记忆可追溯**：显示来源会话和存储时间
- **用户可控**：可在记忆面板调整重要度、删除或筛选记忆
</details>

<details>
<summary><b>🔌 多模型供应商</b> — 选择模型，而非被应用绑定</summary>

20+ 预置供应商，覆盖三类：

| 类型 | 供应商 |
|------|--------|
| **海外** | OpenAI、Anthropic、Gemini、Groq、Together、Mistral、OpenRouter、DeepInfra、Fireworks |
| **国内** | DeepSeek、通义千问、智谱、Moonshot、豆包、百川、零一万物、阶跃星辰 |
| **中转** | OpenCode、API2D、AIHubMix、DeepBricks + 自定义模板 |

模型 ID 从各供应商 `/models` 接口动态拉取，不在本地硬编码。
</details>

<details>
<summary><b>🤖 多 Agent 协作</b> — 一支助手团队</summary>

创建多个性格和专长各异的助手，对话中随时委派任务：

- `@助手名` 提及委派
- 可视化任务卡片逐步展示委派进度
- 团队任务支持轮询执行
</details>

<details>
<summary><b>🔧 Skill 系统 + MCP</b> — 无限扩展</summary>

- **30+ 内置工具**：文件读写、网页搜索、知识库、日历、剪贴板、计算器、短信、闹钟、贴纸、图片/视频生成等
- **`.skill.json` 导入**：创建并分享带参数 Schema 的自定义 Skill
- **MCP 协议**：连接外部 MCP 服务器，动态扩展工具能力（支持 OAuth、SSE 传输、自动发现）
</details>

### 交互体验

<details>
<summary><b>🎤 流式语音识别</b> — 自然说话，实时看到文字</summary>

- DashScope Paraformer 或 Step Whisper API
- 边说边显示部分结果
- 手势交互：长按录音、上滑取消
- 实时振幅波形可视化
</details>

<details>
<summary><b>🖼️ 多模态输入</b> — 图片、PDF、文档</summary>

- **OCR**：ML Kit 离线中文识别，自动提取文字加入对话上下文
- **PDF 解析**：pdfbox-android 提取全文
- **自动识别格式**：TXT、DOCX、EPUB 等
- **图片生成**：内置 DALL-E / Gemini 支持
</details>

<details>
<summary><b>🌐 联网搜索</b> — 实时信息</summary>

- **Jina AI Reader**：免费层级，返回干净 Markdown 摘要
- **Bing Web Search**：Jsoup 结构化结果提取
- **自定义 API**：兼容 SearXNG、Tavily 等端点
- **优雅降级**：抓取失败时回退到搜索摘要
</details>

<details>
<summary><b>💬 主动消息</b> — Muse 主动开启对话</b></summary>

像真实伙伴一样，Muse 可在你许久未联系时主动发消息：

- 平滑间隔滑块（连续可调）
- 时间窗口控制（默认 8:00 – 22:00），支持跨午夜
- 仅 Agent 模式触发，不打断任务工作流
</details>

<details>
<summary><b>🗣️ 文字转语音</b> — 听回复</summary>

- 系统 TTS，零 APK 体积开销
- 可选云端 TTS（OpenAI、MiniMax、Edge）获得更高质量
- 音频路由：扬声器、听筒、蓝牙
- 每个助手可独立配置语速、音调和语言
</details>

### 视觉与个性化

<details>
<summary><b>🎨 6 套 handcrafted 主题</b> — 暖纸风格</summary>

| 主题 | 浅色 | 深色 |
|:------|:---:|:----:|
| 暖纸（默认） | ✅ | ✅ |
| 樱花 | ✅ | ✅ |
| 海洋 | ✅ | ✅ |
| 春日 | ✅ | ✅ |
| 秋意 | ✅ | ✅ |
| AMOLED | ✅ | ✅ |

另有 8 套色盲友好的精选配色用于自定义主题，每套主题完整定义所有 Material 3 颜色角色。
</details>

<details>
<summary><b>📱 Markdown 渲染</b> — 丰富的对话展示</summary>

- 语法高亮代码块（20+ 语言）带行号
- KaTeX 数学公式
- Mermaid 流程图（`securityLevel: 'strict'`）
- 自动识别纯文本 URL，支持点击/长按手势
- 链接二次确认弹窗，防止误触
</details>

<details>
<summary><b>📦 表情包贴纸</b> — 生动反应</summary>

- 导入 zip 压缩包，按文件夹结构自动分类
- 概率自动发送（0–100% 滑块）
- 分类筛选 chips + 预览网格
- 长按删除
</details>

<details>
<summary><b>🔒 安全优先</b></summary>

- 应用 PIN 锁，失败 5 次后指数退避锁定 30 秒
- 敏感配置经 Android Keystore AES-256-GCM 加密
- 云备份使用用户密码加密（PBKDF2 + AES-256-GCM）
- `allowBackup=false`，禁止 ADB 备份提取
- WebView 清理 LLM 输出，禁止 `<iframe>`、`<form>`、`javascript:` 执行
- PIN 验证拦截 Deep Link，防止越权
</details>

### 平台能力

- **内置更新检查**：通过 GitHub Releases API
- **Wear OS 风格小组件**：Glance Compose
- **嵌入式 Web 服务器**：Ktor + JWT + mDNS，局域网 API 访问
- **配置导入**：支持 CherryStudio / Chatbox
- **备份与恢复**：本地文件 + S3 / WebDAV 云同步
- **会话历史全文搜索**：Room FTS
- **定时任务**：WorkManager 兜底
- **使用统计**：热力图可视化
- **崩溃处理**：安全模式，崩溃循环时跳过 Koin 初始化，一键导出日志

---

## 🏗️ 架构设计

```
Muse
├── app/          # UI（Compose）、ViewModel、平台服务、数据层
├── ai/           # 供应商抽象（OpenAI、Anthropic、Gemini）、图片生成
├── memory/       # 四层记忆系统（事实提取、摘要、编译聚合）
├── common/       # 共享工具（日志、JSON、协程辅助）
└── material3/    # Material 3 颜色工具
```

### 关键设计决策

- **全屏页面替代弹窗**：`ModalBottomSheet` 在嵌套滚动 + 输入法场景下会卡死
- **模型 ID 动态拉取**：从不硬编码，从 `/models` 实时获取
- **自定义 `resultOf{}`**：正确重新抛出 `CancellationException`（标准库 `runCatching` 不会）
- **热配置 `@Volatile` 缓存**：修改立即生效，无需重启
- **100% Kotlin**：零 Java 文件

---

## 🚀 快速开始

### 环境要求

- Android Studio Hedgehog（2024.1.1）或更新版本
- JDK 17+
- Android SDK 35
- 任意供应商 API Key（OpenAI、Gemini、DeepSeek 等）

### 构建与安装

```bash
git clone https://github.com/5352124/Muse.git
cd Muse

./gradlew :app:assembleDebug    # 构建 Debug APK
./gradlew :app:installDebug     # 安装到已连接设备
```

### 首次运行

1. 打开应用 → 引导页介绍核心功能
2. 设置你的名字和助手的名字
3. 添加模型供应商（或使用预置模板）
4. 开始对话 —— Muse 从此记住一切

> **提示**：基础对话完全离线运行。只有供应商 API、联网搜索、云备份需要网络，且均为可选开启。

---

## 📖 技术栈

| 类别 | 选择 |
|------|------|
| **语言** | Kotlin 2.0（100% Kotlin） |
| **UI** | Jetpack Compose + Material 3 |
| **架构** | 单 Activity、MVVM、Koin 依赖注入 |
| **数据库** | Room（SQLite + FTS）+ DataStore Preferences |
| **依赖注入** | Koin |
| **网络** | OkHttp（SSE）、Ktor（嵌入式服务器） |
| **图片** | Coil（SVG + GIF） |
| **OCR** | ML Kit 中文识别 |
| **语音识别** | DashScope Paraformer、Step Whisper、Vosk 离线 |
| **序列化** | kotlinx.serialization |
| **小组件** | Glance Compose |
| **PDF** | pdfbox-android |
| **HTML** | Jsoup |
| **TTS** | 系统 TTS + OpenAI / MiniMax / Edge 云端 TTS |

---

## 🔒 隐私安全

Muse 采用**离线优先**设计：

- ✅ 所有对话、记忆、知识库保存在本地（Room 数据库）
- ✅ 无需账号 —— 无遥测、无分析、无数据收集
- ✅ 联网功能（联网搜索、云端语音识别、云备份）默认关闭
- ✅ 应用 PIN 锁防止未授权访问
- ✅ 备份使用你自己的密码加密（PBKDF2 + AES-256-GCM）
- ✅ 崩溃日志仅本地保存，通过安全模式手动导出

---

## 📚 文档导航

- [项目概述](01-overview.md) — 定位、核心理念、版本信息
- [技术栈](02-tech-stack.md) — 全技术组件明细
- [项目结构](03-project-structure.md) — 目录组织
- [核心流程](04-core-flows.md) — 聊天流 / Transformer Pipeline
- [维护指南](09-maintenance-scenarios.md) — 常见场景 / 版本发布
- [版本历史](12-version-history.md) — 完整变更日志
- [已知问题](13-known-issues.md) — 技术债清单
- [开发规范](15-development-standards.md) — 编码/编译/构建规范

完整索引见 [_sidebar.md](_sidebar.md)。

---

## 🤝 参与贡献

- **Bug 反馈** → [提交 issue](https://github.com/5352124/Muse/issues)
- **功能建议** → [发起讨论](https://github.com/5352124/Muse/discussions)
- **点亮 Star** → 帮助更多人发现 Muse

## 📄 开源协议

自 v1.119 起，Muse 采用 **GNU General Public License v3** 开源。

- 项目全部源码、资源和衍生作品均为 GPL v3
- 第三方依赖各自保留原协议 —— 详见 [NOTICE](../NOTICE)

完整协议文本：[LICENSE](../LICENSE)
