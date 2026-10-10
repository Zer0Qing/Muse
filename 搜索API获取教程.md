# 内置搜索 API 获取教程

Muse 内置了 **13 家**搜索/抓取服务，分两类：**免密钥**（开箱可用）与**需密钥**（自己申请后填入）。本文逐个说明去哪儿申请、怎么填、以及各家的特点与免费额度。

> 入口：设置 → 搜索（Web 搜索）。选中供应商后，填「API Key」；部分供应商还能改「接口地址」（endpoint），留空即用默认地址。
> 想省事：**Bing HTTP / Baidu / SearXNG / Jina** 四家不开通任何账号就能用。

---

## 一、免密钥（无需申请，选上即用）

### 1. Bing HTTP
- **类型**：网页抓取
- **申请**：不需要
- **怎么用**：设置里把默认路径选为 `Bing HTTP` 即可
- **特点**：中文结果好，稳定性依赖 Bing 页面结构，偶发变动
- **注意**：这是页面抓取而非官方 API，请求过频可能被限流

### 2. Baidu
- **类型**：网页抓取
- **申请**：不需要
- **怎么用**：选中 `Baidu` 即可
- **特点**：中文覆盖最全，适合国内内容
- **注意**：同为页面抓取，结果解析依赖百度页面结构

### 3. SearXNG
- **类型**：元搜索（聚合多个搜索引擎）
- **申请**：不需要（用公共实例）；也可自建
- **默认地址**：`https://searx.be`
- **怎么用**：选中 `SearXNG`；有自建实例时在「接口地址」填自己的域名
- **特点**：隐私友好、结果来源广
- **注意**：公共实例有速率限制、偶发不可用；**自建实例体验最好**

### 4. Jina Reader Search
- **类型**：AI 搜索 + 正文抓取
- **申请**：不需要（免费层）；要更高额度可去 https://jina.ai 注册拿 Key
- **默认地址**：`https://s.jina.ai/`（另有国内镜像 `https://s.jinaai.cn/`，App 会两个都试）
- **怎么用**：选中 `Jina`；没有 Key 就留空，有 Key 就填进「API Key」
- **特点**：直接把网页正文抓回来，适合需要内容而非链接的场景
- **注意**：**这是唯一一个"无 Key 也官方支持"的商业服务**，免费层有速率限制

---

## 二、需密钥（去官网申请）

### 5. Tavily
- **类型**：AI 搜索 API（专为 LLM 设计）
- **官网**：https://tavily.com
- **申请步骤**：
  1. 打开 tavily.com，用邮箱/Google 账号注册
  2. 进 Dashboard → API Keys，复制形如 `tvly-xxxx` 的 Key
  3. 粘贴进 Muse 的「API Key」
- **默认地址**：`https://api.tavily.com`
- **免费额度**：注册送每月免费调用额度（约 1000 次/月，以官网为准）
- **特点**：返回结构化摘要，AI 场景最省心

### 6. Brave Search
- **类型**：独立索引的搜索引擎 API
- **官网**：https://brave.com/search/api/
- **申请步骤**：
  1. 打开官网 → Get Started / Sign up
  2. 选 **Free** 计划（需绑卡验证，免费层不扣费）
  3. 在 Dashboard 复制 API Key（形如 `BSA...`）
- **默认地址**：`https://api.search.brave.com/res/v1`
- **免费额度**：免费层约 2000 次/月、1 次/秒（以官网为准）
- **特点**：自建索引（不依赖 Google/Bing），英文结果质量高

### 7. Serper
- **类型**：Google 搜索结果 API（第三方）
- **官网**：https://serper.dev
- **申请步骤**：
  1. 注册账号（邮箱/Google）
  2. Dashboard 直接显示 API Key
  3. 新账号通常送 2500 次免费额度
- **默认地址**：`https://google.serper.dev`
- **特点**：拿 Google 结果最便宜的途径，速度快
- **注意**：免费额度用完后需充值

### 8. Bocha（博查）
- **类型**：中文 AI 搜索 API（国内）
- **官网**：https://bochaai.com
- **申请步骤**：
  1. 注册账号，完成实名（国内服务通常需要）
  2. 控制台创建 API Key
- **默认地址**：`https://api.bochaai.com/v1`
- **特点**：**中文内容覆盖好**，适合国内资讯/百科类查询
- **注意**：主要面向国内，访问快

### 9. Metaso（秘塔搜索）
- **类型**：中文 AI 搜索 API
- **官网**：https://metaso.cn
- **申请步骤**：
  1. 打开 metaso.cn，注册并登录
  2. 进开放平台/API 页面申请 Key
- **默认地址**：`https://metaso.cn/api/v1`
- **特点**：中文语义理解强，摘要质量高

### 10. Zhipu（智谱 Web Search）
- **类型**：智谱 AI 的联网搜索能力
- **官网**：https://open.bigmodel.cn
- **申请步骤**：
  1. 注册智谱开放平台
  2. 在「API Keys」里创建 Key
  3. **注意**：用的是智谱的 API Key（和调用 GLM 模型同一个），但该 Key 需要开通 Web Search 权限
- **默认地址**：`https://open.bigmodel.cn/api/paas/v4`
- **特点**：如果你已经在用智谱模型，一个 Key 通用

### 11. Exa
- **类型**：面向 AI 的语义搜索 API
- **官网**：https://exa.ai
- **申请步骤**：
  1. 注册 exa.ai
  2. Dashboard → API Keys 复制（形如 `exa-...`）
- **默认地址**：`https://api.exa.ai`
- **免费额度**：注册送免费额度（以官网为准）
- **特点**：语义检索（按意思找，不按关键词），适合研究类查询

### 12. Firecrawl
- **类型**：网页抓取 + 结构化提取
- **官网**：https://firecrawl.dev
- **申请步骤**：
  1. 注册 firecrawl.dev
  2. Dashboard 复制 API Key（形如 `fc-...`）
- **默认地址**：`https://api.firecrawl.dev/v1`
- **免费额度**：注册送免费额度（以官网为准）
- **特点**：**强在抓取**——把整页内容清洗成干净文本，适合"给我这个页面的内容"

### 13. Perplexity
- **类型**：AI 问答式搜索 API
- **官网**：https://www.perplexity.ai（开发者入口在 docs.perplexity.ai）
- **申请步骤**：
  1. 注册 Perplexity 账号
  2. 进 API 平台（需单独开通，绑定支付方式）
  3. 创建 API Key（形如 `pplx-...`）
- **默认地址**：`https://api.perplexity.ai`
- **特点**：直接返回带引用的答案，不是链接列表
- **注意**：**没有免费层**，需充值；按调用计费

---

## 三、怎么选（按场景）

| 你的需求 | 推荐 |
|---|---|
| 完全不想折腾 | **Bing HTTP** 或 **Jina**（免密钥） |
| 中文内容为主 | **Baidu**（免）、**Bocha**（需 key，质量更好） |
| AI 场景省心、要摘要 | **Tavily** |
| 拿 Google 结果 | **Serper** |
| 语义检索/研究 | **Exa** |
| 要把网页内容抓回来 | **Firecrawl** 或 **Jina** |
| 已有智谱模型 Key | **Zhipu**（一个 Key 通用） |
| 想要问答式结果 | **Perplexity** |

**建议**：同时配 1 个免密钥的（保底）+ 1 个付费的（质量），Muse 会按策略择优。

---

## 四、常见问题

**Q：填了 Key 还是搜不到？**
A：按顺序排查：① Key 是否复制完整（无多余空格）；② 该 Key 是否开通了对应服务（Zhipu 尤其要注意）；③ 「接口地址」是否被误改（留空即默认）；④ 账户是否有余额/额度。

**Q：能同时配多家吗？**
A：能。配置区里每家的 Key 是分开存的，切换供应商时自动带上各自的 Key。

**Q：endpoint 什么时候要改？**
A：三种情况：用自建 SearXNG；用中转/代理地址；官方换了域名。不确定就留空。

**Q：这些 Key 存在哪里？安全吗？**
A：存在本机应用数据里，随备份走。不要分享含 Key 的备份文件。

---

## 五、一句话速查表

| 供应商 | 需要 Key | 默认端点 | 主要优势 |
|---|---|---|---|
| Bing HTTP | 否 | 页面抓取 | 免配置、中文好 |
| Baidu | 否 | 页面抓取 | 中文覆盖全 |
| SearXNG | 否 | `https://searx.be` | 元搜索、隐私 |
| Jina | 否（可选） | `https://s.jina.ai/` | 正文抓取 |
| Tavily | 是 | `https://api.tavily.com` | AI 搜索摘要 |
| Brave | 是 | `https://api.search.brave.com/res/v1` | 自建索引 |
| Serper | 是 | `https://google.serper.dev` | Google 结果 |
| Bocha | 是 | `https://api.bochaai.com/v1` | 中文 AI 搜索 |
| Metaso | 是 | `https://metaso.cn/api/v1` | 中文语义 |
| Zhipu | 是 | `https://open.bigmodel.cn/api/paas/v4` | 智谱生态通用 |
| Exa | 是 | `https://api.exa.ai` | 语义检索 |
| Firecrawl | 是 | `https://api.firecrawl.dev/v1` | 网页抓取提取 |
| Perplexity | 是 | `https://api.perplexity.ai` | 问答式搜索 |
