# 链路 4：渠道与后台可靠性 — 去重基线

## 已确认修复/设计决策（不得再报）
- ChannelInbox 可靠派发：PENDING 先落盘、PROCESSING 恢复、队列满拒绝、BLOCKED(CHECKPOINT_UNREADABLE) 不循环重试。
- Telegram 整批入站才推进 offset；WeClaw 游标/context token SecureKeyStore 加密持久化，写失败不推进；QQ 绑定原 sourceEventId。
- 渠道恢复闸门：ChannelInbox/ChannelConversationStore 在 ProcessWriteGate.restoring 时 fail-closed，webhook 异常返回 503 触发平台重试。
- 群聊/定时任务/主动消息/朋友圈写点闸门全落地（AUD-20261006-* 系列）。
- 渠道 Agent 工具 checkpoint 加密、副作用不确定阻断重跑；at-least-once 是声明的设计边界。
- 渠道媒体：清理前 attach inbox+历史收集 mediaPath，保护被引用文件。
- 微信媒体 CDN 下载+AES 解密、语音服务端 ASR；群消息 group: 前缀路由。

## 已知未解决（可引用编号补充新证据）
- P1 #13-16/#18-19（渠道生命周期与落盘历史项）——但其中很多可能已随 ChannelInbox 落地，**先核实再引用**；已实现的标注"已闭环"。
- 渠道 ON_STOP 停接收器、指数退避抖动——核实是否已落地。
- 真实平台矩阵（QQ/飞书/Telegram/钉钉/微信 iLink）无设备/账号验收——已知，不算发现。

## 本链路重点探查方向
1. **五渠道接收器的生命周期管理现状**：前后台切换/进程回收时接收器启停；退避重试是否有上限与抖动；断网恢复后的积压处理。
2. ChannelInbox dispatcher：单线程串行还是并发？一个渠道阻塞（如飞书长连接断）会不会饿死其他渠道？
3. A2A/Tasker 入站与渠道入站共享 ChannelConversationStore 吗？并发写同一会话的锁/幂等。
4. webhook 接收端 HTTP 超时/慢 body deadline（曾修过慢 body deadline——核实覆盖所有平台接收器）。
5. 通知回传（Tasker 结果通知、渠道回复通知）的通知渠道/权限/免打扰处理。
6. 媒体文件生命周期：临时下载文件清理、解密文件落盘位置权限。
