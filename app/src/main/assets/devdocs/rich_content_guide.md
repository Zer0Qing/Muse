<!-- devdoc: 内部开发文档 -->
# 富媒体输出

## 概述

LLM 在回复中通过特殊代码块语言标识触发富媒体卡片渲染,支持 SVG 图形 / HTML 卡片 / 图表。
渲染入口在 `MarkdownText.kt` 的 CodeBlock 分支,由 `RichContentCard.kt` 接管 svg/html/chart 三类。

## 支持的卡片类型

### SVG 图形
用 ```svg 代码块,内容是标准 SVG XML。
LLM 可以生成简单的图标/示意图/流程图。

渲染方式:WebView(包进 HTML,JavaScript 禁用)。

示例:
```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
  <circle cx="50" cy="50" r="40" fill="steelblue"/>
</svg>
```

### HTML 卡片
用 ```html 代码块,内容是简单 HTML。
- 支持 CSS 样式
- JavaScript 已禁用(安全考虑,不要输出 script)
- 不要包含外部资源引用

渲染方式:WebView(JavaScript 禁用,透明背景,支持暗色模式 media query)。

### 图表
用 ```chart 代码块,内容是 JSON:
```json
{
  "type": "bar",
  "data": {
    "labels": ["A", "B", "C"],
    "values": [10, 20, 15]
  }
}
```

渲染方式:本期简化为 JSON 文本展示(等宽字体),后续可接入 Vico 图表库做真正的柱状图/折线图。
type 取值:bar / line / pie。

## 调用建议
- 用户说"画个图"/"可视化" → SVG 或图表
- 用户说"做个卡片" → HTML
- 用户说"柱状图/折线图" → chart(type=bar/line)
- 不要滥用,只在用户明确要求时输出富媒体

## 图片生成(generate_image 工具)最佳实践

LLM 通过 `generate_image` 工具(非富媒体代码块)调用 ImageService 生成位图。与上面的 SVG/HTML 富媒体不同,这是真实图片生成。

### 工具参数
- `prompt`(必填):图片描述,建议英文(多数图像模型英文效果更好),50-300 字。
- `size`(可选,默认 1024x1024):支持 `1024x1024`(方)/`1024x1792`(竖)/`1792x1024`(横)等,具体看模型支持。
- `model`(可选):留空时由 ImageService 按 ProviderSpecificConfig / Catalog 兜底。显式指定时需是已配置模型。
- `reference_image`(可选):参考图(图生图)。**LLM 不应主动填此参数** —— 由用户在工具审批卡片中从相册选择后注入。LLM 若判断需要参考图,应在回复中提示用户"请在弹出的卡片中选择参考图"。

### Prompt 编写技巧
1. **主体明确**:开头直接说画什么,如 "a cute orange cat sitting on a windowsill"。
2. **风格关键词**:加风格词提升质感,如 `photorealistic` / `oil painting` / `watercolor` / `anime style` / `3D render` / `pixel art` / `minimalist illustration`。
3. **细节描述**:光影、构图、色调、氛围,如 `soft morning light, shallow depth of field, warm tones, cozy atmosphere`。
4. **负面提示(部分模型支持)**:在 prompt 末尾加 `--no text, watermark, blurry` 排除不想要的元素(注意并非所有模型支持此语法)。
5. **避免抽象指令**:不要写"画出孤独的感觉"(模型难理解),改为具象场景"一个人独自坐在空荡的咖啡馆窗边,雨夜"。
6. **避免文字要求**:图像模型生成文字普遍不准,不要要求"画一个写有'Hello'的牌子"。若必须含文字,告诉用户文字可能不准确。

### 参考图(图生图)使用时机
- 用户上传一张图要求"改成 X 风格" → 提示用户在审批卡片选参考图,再用 `reference_image` 参数。
- 用户要求"基于这张图再画一张类似的" → 同上。
- 用户要求"把这张照片里的人换成猫" → 图生图 + 文字描述改动。
- **不要在无参考图时假装图生图**:若用户没选参考图,LLM 应走纯文生图路径,不要瞎填 `reference_image`。

### 何时调用 generate_image
- ✅ 用户明确说"画一张""生成一张图""画个 X" → 调用
- ✅ 用户要求可视化但 SVG/图表 不适合(如"画一个风景") → 调用
- ❌ 用户只是闲聊提到图片(如"我昨天拍了张照") → 不调用
- ❌ 用户要的是数据可视化(柱状图/折线图) → 用 `chart` 代码块,不用 generate_image
- ❌ 用户要的是示意图/流程图 → 用 `svg` 代码块,不用 generate_image

### 失败处理
- `skill_image_not_configured`:提示用户去 设置 → 模型与服务 → 图片生成 配置 Provider 和模型。
- `skill_image_no_result`:模型返回空,可能是 prompt 触发安全过滤,建议用户调整 prompt 后重试。
- `skill_image_failed`:调用异常(网络 / 鉴权 / 配额),坦诚告知失败原因,不编造图片。
- **不要假装生成成功**:若工具返回错误,不要在回复中描述一张不存在的图片。
