# 人民币套餐与额度设计

## 领域对象

`Plan`、`PriceVersion`、`Entitlement`、`Subscription`、`Invoice`、`Payment`、`CreditLedger`、`UsageRecord`、`Coupon`、`Refund`。

## 原则

- 金额统一使用分，不使用浮点数。
- 价格版本支持未来生效、历史追溯和回滚。
- 已支付周期使用订单中的价格快照，不因后台调价改变。
- 所有额度扣减使用不可变账本 + 幂等键。
- BYOK 用户不扣模型托管额度，但可扣存储、记忆、文件、Agent 和高级功能额度。
- Hosted 用户在模型请求前预扣或预授权，结束后按实际 usage 结算，多退少补。
- 模拟支付先行；支付宝通过 `PaymentProvider` 接口接入，生产凭据不进入代码库。

## 后台首批可调参数

套餐名称、月价、年价、试用期、消息额度、Token 额度、Agent 次数、图片/视频次数、存储、MCP 数量、并发数、BYOK 开关、Hosted 开关、超额策略和 Feature Flag。
