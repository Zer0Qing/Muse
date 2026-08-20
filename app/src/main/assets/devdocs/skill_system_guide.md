<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# Skill 系统使用指南 工具调用 function calling

Muse 的 Skill 系统是 LLM 进行 function calling 的工具集合。当用户问"你有哪些工具""你能调用什么""skill 是什么""怎么自定义工具""怎么创建技能""能帮我做一个技能吗"时参考本文档。

内置 skill 共 23 个(以源码 SkillExecutor.kt 的 BUILT_IN_SKILLS 为准)。其中 8 个为 install_skill 白名单(用户自定义/LLM 自安装 skill 的 implementationKotlin 只能引用这 8 个):

8 个白名单 skill(核心能力):

1. read_file — 读取应用沙盒内文本文件(上限 1MB)。参数: path(相对 filesDir), offset/length(可选,按"行"分段,offset=起始行号,length=读取行数,非字节)。调用时机: 需要读用户存储的笔记/数据文件时。
2. write_file — 写入文本到沙盒文件。参数: path, content, append(可选,默认 false 覆盖)。返回值含内容预览(前 200 字符)。调用时机: 需要持久化笔记/数据/生成的代码时。
3. http_get — 发起 HTTP GET。参数: url, headers(可选 JSON)。响应上限 1MB。调用时机: 调用 REST API 获取数据。
4. http_post — 发起 HTTP POST。参数: url, body(字符串,需自行序列化 JSON), content_type(默认 application/json), headers(可选)。调用时机: 提交数据到 API。
5. web_search — 用配置好的搜索引擎(SearXNG/Tavily/Bing)搜索网页,返回标题/URL/摘要。参数: query, max_results(1-10,默认 5)。调用时机: 用户需要实时信息、最新新闻、概念解释。
6. web_fetch — 抓取指定 URL 网页正文(去 HTML 标签,返回纯文本)。参数: url, headers(可选)。抓取上限 20 万字符,返回上限 5 万字符。调用时机: 读 web_search 返回的 URL 全文(先搜后读两步)。
7. knowledge_search — 在用户知识库全文搜索(标题 + 内容)。参数: query, threshold(可选,0-1 小数制,默认 0.3)。调用时机: 用户问项目自身功能/已导入文档相关问题(开发文档 fileType=devdoc 也在此)。
8. arxiv_search — arXiv 学术论文搜索。参数: query(英文为佳), max_results。调用时机: 学术研究/论文问题。

其余内置 skill(不在 install_skill 白名单,直接由系统注册): delete_file(支持 paths 批量)、file_exists、list_dir、file_download、read_public_file、save_to_downloads、list_public_files、list_skills、uninstall_skill、install_skill、delegate_agent、channel_reply、channel_pass、channel_read_context、task_plan、update_plan_step、generate_image、translate、generate_qr、list_stickers、send_sticker 等。完整列表见 SkillExecutor.kt 的 BUILT_IN_SKILLS。

---

# 创建技能完整教程(用户/LLM 通用)

## 什么是技能

技能(skill)是一个"把常用操作打包成可复用工具"的定义。用户创建技能后,LLM 在后续对话中看到合适的场景会自动调用它,不需要每次重复描述步骤。

## 三种创建路径

| 路径 | 谁操作 | 方式 | 适用场景 |
|---|---|---|---|
| A. 让 LLM 帮你创建 | 用户对话 | 告诉 LLM"帮我创建一个 XX 技能",LLM 用 install_skill 完成 | 最常见,推荐 |
| B. 导入 .skill.json 文件 | 用户 | 设置 → Skill 页导入文件 | 分享/备份/迁移 |
| C. 写代码打包 skillpkg | 开发者 | manifest.json + JS 脚本 | 复杂技能/JS 引擎 |

**路径 A 是推荐方式**:用户不需要懂任何格式,直接对 LLM 说"帮我做一个'查菜谱'技能",LLM 负责生成 .skill.json 并安装。

## .skill.json 完整格式(install_skill / 文件导入通用)

```json
{
  "id": "fetch_recipe",
  "name": "查菜谱",
  "description": "根据菜名查询家常菜谱的做法和食材清单。当用户问某道菜怎么做时使用。",
  "parametersJson": "{\"type\":\"object\",\"properties\":{\"dish\":{\"type\":\"string\",\"description\":\"菜名,如'红烧肉'\"}},\"required\":[\"dish\"]}",
  "requiredJson": "[\"dish\"]",
  "implementationKotlin": "web_search",
  "category": "custom"
}
```

## 字段说明

| 字段 | 必填 | 说明 | 规则 |
|---|---|---|---|
| id | ✅ | 技能唯一标识 | 小写字母/数字/下划线/连字符,如 `fetch_recipe`;不能与内置 skill/工具同名(会冲突) |
| name | ✅ | 展示名称 | 中文即可,如"查菜谱" |
| description | ✅ | 给 LLM 看的说明 | **最重要**:写清"什么场景用、输入什么、输出什么";写得好 LLM 才知道何时调用 |
| parametersJson | 可选 | 参数定义 | JSON Schema 的字符串形式(见下方参数写法) |
| requiredJson | 可选 | 必填参数 | 数组字符串,如 `["dish"]` |
| implementationKotlin | ✅ | 底层实现 | **只能选 8 个白名单之一**:read_file / write_file / http_get / http_post / web_search / web_fetch / knowledge_search / arxiv_search |
| category | 可选 | 分类 | 默认 `custom` |

## parametersJson 参数写法(JSON Schema)

```json
{
  "type": "object",
  "properties": {
    "参数名1": { "type": "string", "description": "参数说明,中文" },
    "参数名2": { "type": "integer", "description": "数量上限" },
    "参数名3": { "type": "boolean", "description": "是否开启" }
  },
  "required": ["参数名1"]
}
```

- type 支持: string / integer / number / boolean / array / object
- description 用中文写清楚,LLM 靠它决定传什么值
- required 列出必填参数;非必填的参数不要放进去

## 按用途选实现(最关键的一步)

| 想做的技能 | 选这个实现 | 举例 |
|---|---|---|
| 查信息/搜索网页 | web_search | 查菜谱、查电影、查攻略 |
| 读网页全文 | web_fetch | 读文章、读公告 |
| 调用 API 拿数据 | http_get | 查汇率、查快递、查天气 API |
| 提交数据到 API | http_post | 发消息、提交表单 |
| 读写本地文件 | read_file / write_file | 记账本、待办、收藏夹 |
| 查知识库/内部文档 | knowledge_search | 查用户导入的文档 |
| 查学术论文 | arxiv_search | 论文检索 |

## install_skill 调用示例(LLM 视角)

当用户说"帮我创建一个能查菜谱的技能"时,LLM 应:

1. 确定实现: 查菜谱 → web_search
2. 构造 skill_json(注意 parametersJson 是字符串,需要转义):
```
install_skill(skill_json = {"id":"fetch_recipe","name":"查菜谱","description":"根据菜名查询家常菜谱做法和食材。用户问某道菜怎么做时调用。","parametersJson":"{\"type\":\"object\",\"properties\":{\"dish\":{\"type\":\"string\",\"description\":\"菜名\"}},\"required\":[\"dish\"]}","requiredJson":"[\"dish\"]","implementationKotlin":"web_search","category":"custom"})
```
3. 安装成功后告诉用户:"已创建'查菜谱'技能,在 设置 → Skill 页可以查看/停用。现在你可以问我任何菜的做法了。"

## 常见错误与修复

| 错误 | 原因 | 修复 |
|---|---|---|
| "缺少 id 字段" | 没写 id | 补 id,用小写 slug |
| "缺少 implementationKotlin 字段" | 没写实现 | 补 8 个白名单之一 |
| "实现不被允许" | implementationKotlin 不在白名单 | 换白名单实现,或拆成多个技能 |
| "id 与内置冲突" | id 撞了 read_file/calculator 等 | 换一个不重复的 id |
| "参数格式错误" | parametersJson 不是合法 JSON Schema | 按上面的参数写法重写 |
| 技能装了但不生效 | description 写得太模糊,LLM 不知道何时调用 | 重写 description:什么场景、输入、输出 |
| 想让技能执行"任意代码" | 不支持 | 技能只能复用白名单实现,这是安全设计 |

## 安装后的管理

- 查看/启停: 设置 → Skill 页
- 删除: 设置 → Skill 页(或 LLM 用 uninstall_skill)
- 分享: 导出 .skill.json 文件给他人,对方在 Skill 页导入

## 系统提示注入(给 LLM 的边界)

- 技能是"按需调用"的工具,不是每次对话都自动调;根据用户意图判断
- 用户想自定义工具时,主动提出"我可以帮你创建一个技能",并用 install_skill 完成,不要只给步骤
- 用户技能实现受限是安全设计,不要承诺做不到的"任意代码执行"
