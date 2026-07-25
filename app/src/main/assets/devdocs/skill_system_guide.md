<!-- devdoc: 内部开发文档,不向用户展示,LLM 通过 knowledge_search 查询 -->
# Skill 系统使用指南 工具调用 function calling

Muse 的 Skill 系统是 LLM 进行 function calling 的工具集合。当用户问"你有哪些工具""你能调用什么""skill 是什么""怎么自定义工具"时参考本文档。

内置 skill 共 23 个(以源码 SkillExecutor.kt 的 BUILT_IN_SKILLS 为准)。其中 8 个为 install_skill 白名单(用户自定义/LLM 自安装 skill 的 implementationKotlin 只能引用这 8 个):

8 个白名单 skill(核心能力):

1. read_file — 读取应用沙盒内文本文件(上限 1MB)。参数: path(相对 filesDir), offset/length(可选,按"行"分段,offset=起始行号,length=读取行数,非字节)。调用时机: 需要读用户存储的笔记/数据文件时。
2. write_file — 写入文本到沙盒文件。参数: path, content, append(可选,默认 false 覆盖)。返回值含内容预览(前 200 字符)。调用时机: 需要持久化笔记/数据/生成的代码时。
3. http_get — 发起 HTTP GET。参数: url, headers(可选 JSON)。响应上限 1MB。调用时机: 调用 REST API 获取数据。
4. http_post — 发起 HTTP POST。参数: url, body(字符串,需自行序列化 JSON), content_type(默认 application/json), headers(可选)。调用时机: 提交数据到 API。
5. web_search — 用配置好的搜索引擎(SearXNG/Tavily/Bing)搜索网页,返回标题/URL/摘要。参数: query, max_results(1-10,默认 5)。调用时机: 用户需要实时信息、最新新闻、概念解释。
6. web_fetch — 抓取指定 URL 网页正文(去 HTML 标签,返回纯文本)。参数: url, headers(可选)。抓取上限 20 万字符,返回上限 5 万字符。调用时机: 读 web_search 返回的 URL 全文(先搜后读两步)。
7. knowledge_search — 在用户知识库全文搜索(标题 + 内容)。参数: query, threshold(可选,0-1 小数制,默认 0.3,启发式近似评分:标题命中=1.0/内容命中=0.5,非真向量相似度)。调用时机: 用户问项目自身功能/已导入文档相关问题(开发文档 fileType=devdoc 也在此)。
8. arxiv_search — arXiv 学术论文搜索。参数: query(英文为佳), max_results。调用时机: 学术研究/论文问题。

其余内置 skill(不在 install_skill 白名单,直接由系统注册): delete_file(支持 paths 批量)、file_exists、list_dir、file_download、read_public_file(接受 list_public_files 返回的 content:// URI)、save_to_downloads(支持 file_path 转存本地文件)、list_public_files(输出含 uri= 可直接喂给 read_public_file)、list_skills、uninstall_skill、install_skill、delegate_agent、channel_reply、channel_pass、channel_read_context、task_plan、update_plan_step、generate_image、translate、generate_qr、list_stickers、send_sticker 等。完整列表见 SkillExecutor.kt 的 BUILT_IN_SKILLS。

自我扩展工具:
- install_skill — LLM 自己生成 .skill.json 格式的 skill 定义并入库。参数: skill_json(JSON 字符串)。implementationKotlin 必须是上述 8 个白名单实现之一(read_file/write_file/http_get/http_post/web_search/web_fetch/knowledge_search/arxiv_search),不支持任意代码执行。安装后用户在 设置 → Skill 页可查看/启停。

自定义 skill: 用户可在 设置 → Skill 页导入 .skill.json 文件,但 implementationKotlin 字段必须是 8 个白名单实现之一,否则执行时返回"未知 skill 实现"。

skillIdsJson 字段(AssistantEntity): 默认 "[]" 表示启用所有 skill;可指定数组如 ["knowledge_search","web_search"] 启用子集。

注意: skill 是"按需调用"的工具,不是每次对话都自动调。LLM 应根据用户意图判断是否调用。
