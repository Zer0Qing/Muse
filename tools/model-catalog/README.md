# 模型清单生成

Muse 展示的「这个模型支持什么、上下文多长、最大能输出多少」由一份 JSON 驱动，
运行时的类型是 `io.zer0.ai.core.ModelCatalog`：

```json
{ "schemaVersion": 1, "publishedAt": "...",
  "providers": { "providerId": { "modelId": {
      "name": "...", "context": 1000000, "maxOutput": 128000,
      "image": true, "reasoning": true,
      "toolUse": { "supportsTools": true, "dialect": "openai", "toolResultFormat": "message" },
      "thinkingLevels": ["low","medium","high"]
  } } } }
```

## 为什么要自己攒

之前这份清单完全依赖外部：整套照抄某个上游，或者等别人更新。
问题在于两边的数据各自只覆盖一半事实：

- **上游能力表**知道「这家渠道的工具调用是什么方言、结果怎么回」——只有它带 `toolUse.dialect`；
- **models.dev** 知道「上下文多长、最大输出多少、吃不吃图、支不支持推理」。

分开看都有洞。合起来才完整，而且可以随时按自己的节奏再生一次。

## 数据源

| 角色 | 来源 | 提供 |
|---|---|---|
| 骨架 | 上游 `model-catalog.v2.json` | 渠道集合、模型集合、`toolUse`、`thinkingLevels`、`compat` |
| 补充 | `https://models.dev/api.json` | `limit.context`、`limit.output`、`modalities`、`tool_call`、`reasoning` |

字段优先级：**上游条目 > 上游 fallbacks > models.dev 本渠道 > models.dev 跨渠道**。
越靠前越贴近「在我们这套调用路径上验证过」的事实。

跨渠道兜底是必要的：models.dev 的 openrouter 条目普遍不带 `limit.output`，
但同款模型在官方渠道条目里有。最大输出是模型属性，不是渠道属性。

## 用法

```bash
python tools/model-catalog/build_catalog.py \
    --hana      path/to/model-catalog.v2.json \
    --modelsdev path/to/models_dev_api.json \
    --out       app/src/main/assets/model-catalog.json
```

`models_dev_api.json` 直接从 `https://models.dev/api.json` 下载（约 5.6MB）。
不要用别人打包的精简快照——常见做法是只留 `limit.context`，把 `limit.output` 裁掉，
那样最大输出就永远补不齐。

## 发布

清单同时服务两处：

1. **内置**：`app/src/main/assets/model-catalog.json`，随 APK 发布，离线可用。
2. **远端**：`https://museai.ltd/api/model-catalog.json`，对应
   `ModelCatalogRepository.REMOTE_URL`。换模型不用发版。

上传（先备份旧的再覆盖）：

```bash
ssh root@<host> "cp /var/www/museai.ltd-api/model-catalog.json \
    /var/www/museai.ltd-api/model-catalog.json.bak-$(date +%Y%m%d-%H%M)"
scp app/src/main/assets/model-catalog.json \
    root@<host>:/var/www/museai.ltd-api/model-catalog.json
```

注意 `/var/www/museai.ltd-api/` 与 `/var/www/museai.ltd/assets/` 是两个不同的东西，
后者是官网静态资源目录，不是模型清单的入口。

## 校验

生成脚本会打印覆盖率（渠道数、模型数、有上下文、有最大输出、有工具规格）。
当前基线：35 家 / 1032 个模型 / 上下文 98% / 最大输出 96%。

剩余缺口集中在 together、openai、volcengine 的少数边缘模型，两份源都没有数据，
不做推断——猜出来的数字比空值更危险。