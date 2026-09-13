# Web 能力边界

## Web 完整覆盖

聊天、流式响应、Provider、BYOK、托管模型、会话、Assistant、Persona、Mood、记忆、RAG、文件、文档解析、OCR、图片/视频任务、TTS/ASR、搜索、工具、Skill、MCP、Agent、群聊、导入导出、主题、多语言、Web Push、额度、套餐和后台运营。

当前客户端已形成首个可用聊天闭环：会话自动创建、消息持久化、BYOK 直连/服务端代理、流式输出、停止生成、基础 Markdown 代码块和 Assistant 提示词注入。与 Android 体验仍有差距的部分按下方阶段逐步补齐。

## Web 替代

Room → PostgreSQL；DataStore → PostgreSQL + IndexedDB；WorkManager → Worker/队列；Keystore → KMS/信封加密；本地文件 → S3；Android TTS/ASR → Web API + 云端 Provider；前台服务 → 服务端异步任务 + SSE/Web Push。

## 明确不支持

Android 无障碍、Shizuku、Root、跨应用点击/滑动/输入、系统通知监听、短信、联系人、系统日历直接读写、设备电量/蓝牙/Wi-Fi/亮度/音量控制、Android 悬浮窗、小部件、静默安装和其他应用私有数据。
