<!-- devdoc: internal dev doc, not shown to users, LLM queries via knowledge_search -->
# Muse App Features Overview

> Reference this doc when users ask "what can you do", "what is Muse", "what features does this app have", "how to use".
> This document is an internal development document queried by the LLM via the knowledge_search tool. Its content is based on the real code of the muse project and is not shown to users verbatim.

## 1. What is Muse

Muse is an Android AI assistant app. Its core positioning is a "companion LLM client + Agent platform". It integrates chat, multi-assistant persona management, long-term memory, knowledge base retrieval, Skill extensions, scheduled tasks, group chat collaboration, and other capabilities in one app. All data is stored locally (Room database + DataStore).

Main differences from a typical LLM chat client:
- Multi-assistant: each assistant has its own systemPrompt, model, tools, and memory configuration
- Long-term memory: Fact extraction + compilation into a markdown summary injected into the system prompt
- Knowledge base: users can import documents, and the LLM retrieves them full-text via knowledge_search
- Skill system: 8 built-in skills + install_skill self-extension
- Agent collaboration: the delegate_agent tool lets an assistant delegate tasks to other assistants, and group chat lets multiple assistants discuss

## 2. Core Feature Modules

### 1. Chat (ChatScreen)
The main interaction interface, streaming LLM replies. Entry: Home session list → enter a session.

- Markdown rendering: code highlighting (CodeHighlighter), LaTeX formulas (FormulaView), tables, quote blocks, lists
- Long message collapse: auto-collapsed above a threshold; tap to expand
- Message bubble actions: tap/long-press to bring up the action menu, including: copy / resend (regenerate) / delete / edit / quote / delegate (delegate_agent) / translate / read aloud (TTS) / favorite / fork (copy history from that message into a new session) / share (export as Markdown via the system share sheet)
- Quote reply: user messages can carry a `> ` quote block; the quoted content is shown at the top of the bubble
- Temporary model switch: the model capsule in the input bar can temporarily switch the current session's model
- Thinking tags: supports MOOD/thinking tag expand/collapse

### 2. Agent / Assistant (AssistantScreen + AssistantDetailPages)
Multi-assistant persona management. Each assistant is an independent individual with its own systemPrompt, model, tool toggles, and memory configuration. Entry: Settings (设置) → Assistant (助手) / Settings (设置) → Agent.

Assistant details are split into 5 sub-pages:
- Basic: avatar, name, model selection, assistant card export
- Prompt: systemPrompt, messageTemplate, presetMessagesJson
- Extensions: Lorebook world setting, PromptInjection prompt injection, QuickMessage quick messages, Skill toggles
- Memory: memoryEnabled, useGlobalMemory, enableRecentChatsReference, enableTimeReminder
- Advanced: model parameters (temperature / reasoningLevel), request headers

### 3. Memory (MemoryScreen + MemorySettingsPage)
Long-term memory system. Entry: Settings (设置) → Assistant (助手) → Memory sub-page / Settings (设置) → Memory (记忆).

- Fact extraction: the daily pipeline extracts facts from conversations
- Compile injection: Facts are compiled into a markdown summary and injected into the system prompt via MemoryInjectionTransformer
- Pinned Memories: memories pinned by the user/LLM, injected every time, do not decay over time
- Time reminder: when enableTimeReminder is on, time-related memories are injected on demand
- Global/independent memory: useGlobalMemory switches between global shared or assistant-independent

### 4. Knowledge Base (KnowledgeScreen)
Users import documents for the LLM to retrieve. Entry: Settings (设置) → Knowledge Base (知识库).

- Supported formats: txt / md / csv / json
- Retrieval method: the LLM retrieves by title + content full-text via the knowledge_search tool
- RAG vector retrieval: with an EmbeddingProvider configured, semantic retrieval is supported (OpenAI / ONNX local / keyword fallback)
- devdoc entries: entries with fileType="devdoc" are internal development documents, only for LLM queries and not listed in the UI (this document is one of them)

### 5. Skill System (SkillScreen)
8 built-in skills + self-extension. Entry: Settings (设置) → Skill.

- Built-in skills: web_search / web_fetch / knowledge_search / install_skill / delegate_agent / list_stickers / send_sticker, etc.
- install_skill: the LLM generates a skill definition itself and stores it (self-extension)
- User-defined skills: import .skill.json, but implementationKotlin must be an implementation on the whitelist (to prevent the LLM from overriding built-in tools)
- Management: install / uninstall / enable / disable

### 6. Scheduled Tasks (ScheduledTasksScreen)
Cron-expression-based timed triggering of the assistant to generate messages and notifications. Entry: Settings (设置) → Scheduled Tasks (定时任务).

- Standard 5-field cron expression (minute hour day month weekday)
- When triggered, ScheduledTaskRunner calls ChatGenerationService to let the assistant generate a message
- Execution history is recorded in ScheduledTaskExecutionEntity
- Minimum interval 10 minutes

### 7. Proactive Messages (ChatSettingsPage)
The assistant proactively sends messages to the user at fixed intervals and pops a notification (like a WeChat incoming message). Entry: Settings (设置) → Chat (聊天) → Proactive Messages (主动消息).

- Interval options: 1 / 2 / 4 / 8 / 12 / 24 hours
- Actual interval = base interval ± random offset (lower bound 1 hour, to avoid being too frequent)
- Scheduled by ProactiveMessageRunner, with notifications via MuseNotificationManager

### 8. Deep Thinking
Temporarily enabled from the chat input bar's "+" menu. Entry: Chat page → input bar "+" → Deep Thinking (深度思考).

- Enables ReasoningLevel.HIGH (8000-token reasoning budget)
- Only effective for the current session runtime, not persisted to the assistant configuration
- Overrides the assistant's default reasoningLevel

### 9. Web Search
Toggle in the input bar's "+" menu. Entry: Chat page → input bar "+" → Web Search (联网搜索).

- Defaults to Bing scraping cn.bing.com
- Supports SearXNG / Tavily (configured in Settings → Web Search)
- Once enabled, the LLM autonomously decides when to call web_search / web_fetch

### 10. Group Chat (GroupChatScreen)
Multi-agent collaborative discussion. Entry: Home group chat tab / Settings (设置) → Agent.

- Select participating assistants when creating a group chat
- Users can join channels to participate in the conversation
- Types: DM (two-person private chat) / Group (multi-person group)
- Scheduled by GroupChatScheduler for assistants to speak in turn

### 11. Standalone Translate Page (TranslateScreen)
Multi-language translation. Entry: Settings (设置) → Tools (工具) → AI Translate (AI 翻译).

- Supports pasting source text and copying the translation
- Source language / target language selectable
- Translated by the currently configured LLM model

### 12. Custom Theme
Appearance personalization. Entry: Settings (设置) → Appearance (外观).

- Material You dynamic color (dynamicColor): follows the system wallpaper (Android 12+)
- 3-layer fallback: dynamicColor > customTheme > presetTheme
- Custom theme: generates a ColorScheme from a seed color; primary / secondary / tertiary colors customizable
- Preset theme: multiple built-in color schemes
- Font, Markdown spacing, etc. adjustable

### 13. WebServer (WebServerSection)
Embedded Ktor server. Entry: Settings (设置) → Web Server (Web 服务器).

- Remote invocation of app capabilities over HTTP + JWT within the local network
- mDNS service discovery (MdnsService); local network devices can auto-discover
- Used for remote control of Muse from a PC or other devices

### 14. Backup (BackupSection)
Data export/import and cloud backup. Entry: Settings (设置) → Backup (备份).

- Local export / import: full data export to a file, restorable
- Cloud backup: S3 / WebDAV
- Balance query: query the cloud storage balance

### 15. Sticker Library
v1.95 feature. Entry: Settings (设置) → Chat (聊天) → Sticker Library (表情包库).

- The LLM can list available stickers via the list_stickers tool
- The LLM can send stickers to the user via the send_sticker(id=...) tool
- Stickers are imported as zip files and stored independently of Room
- Send probability: 0-100 adjustable (default 30); the model has this probability of calling send_sticker on each reply

### 16. TTS
Text-to-speech. Entry: Settings (设置) → Media (媒体) → TTS.

- System TTS: invokes the Android system TTS engine
- Cloud TTS: OpenAI / MiniMax / Edge (OpenAI-compatible interface format)
- Triggered by the "Read aloud" item in the chat page message menu

### 17. OCR
Image text recognition. Entry: auto-recognized when sending an image on the chat page.

- ML Kit offline recognition
- Supports Chinese + English
- Unified scheduling by OcrManager

### 18. User Profile
Personalized information injection. Entry: Settings (设置) → Appearance (外观) → User Profile (用户画像).

- Fillable: nickname / age / city / MBTI / occupation / interests
- Content is injected into the system prompt so the LLM knows the user better

### 19. Onboarding (OnboardingScreen)
First-launch flow. Entry: first install and launch.

- Step 1: three-page feature introduction (HorizontalPager) + Next / Skip
- Step 2: personalized nickname setup (assistant name + user nickname) + Start Using / Add Model Provider

### 20. MCP (Model Context Protocol)
Connector system. Entry: Settings (设置) → Models & Services (模型与服务) → MCP.

- Manage multiple McpClients
- Supports local and remote MCP services
- McpOAuthFlow supports OAuth login authorization
- Auto-reconnect on disconnect
- Once connected, tools provided by MCP are available to the LLM just like built-in tools

### 21. Multi-Agent Collaboration
delegate_agent tool. Entry: invoked autonomously by the LLM.

- Lets the current assistant delegate a task to another assistant to run a round of LLM
- The available assistant id list is injected into the prompt; the LLM selects the delegation target based on this
- After the sub-assistant finishes, the result is returned to the main assistant

### 22. QR Code Import/Export
Assistant configuration sharing. Entry: Assistant details → Export QR code (导出二维码).

- Assistant configurations can be generated into QR codes for sharing
- Scanning a QR code can import an assistant configuration
- Implemented by QrCodeGenerator / QrCodeScanner

### 23. Prompt Variables
Template variable system. Used in systemPrompt / messageTemplate.

- Syntax: Pebble-compatible subset, in {{ var }} form
- Common variables: {{user_name}} / {{char}} / {{date}} and other user/assistant/time-related variables
- Supported filters: upper / lower / length / default / trim / join / replace / capitalize
- Supports for loops and if conditionals
- Rendered by PebbleTemplateEngine (a zero-dependency pure Kotlin implementation)

### 24. Regex Replacement Rules
Message post-processing. Entry: Assistant details → Extensions.

- RegexMessageTransformer / RegexTransformer
- Performs regex replacement post-processing on LLM output according to rules
- Rules are stored per assistant configuration

### 25. Lorebook
World setting injection. Entry: Assistant details → Extensions → Lorebook.

- Injects world setting entries triggered by keywords
- LorebookTransformer injects the corresponding setting when a keyword is matched in the conversation
- Managed by the standalone LorebookScreen

### 26. PromptInjection
Prompt injection. Entry: Assistant details → Extensions → PromptInjection.

- Injects additional prompts into the system prompt according to conditions
- Handled by PromptInjectionTransformer
- Managed by the standalone PromptInjectionScreen

### 27. QuickMessage
Quick messages. Entry: Assistant details → Extensions → QuickMessage / quick chips in the input bar.

- Preset short phrase chips, one-tap send
- Uses double-brace placeholders {{input}} / {{clipboard}} / {{date}}, auto-rendered by QuickMessageRepository.renderTemplate
- Managed by the standalone QuickMessageScreen

## 3. Settings Sections

The settings page (SettingsScreen) is divided into the following sections, from top to bottom:

### 1. Appearance & Personalization (high-frequency user toggles at the top)
- Theme (dynamicColor / customTheme / presetTheme)
- Font, Markdown spacing
- User profile (nickname / age / city / MBTI / occupation / interests)
- Chat secondary settings (message display / default expand / temperature / advanced)

### 2. Memory & Data
- Memory (MemorySettingsPage)
- Cloud backup (S3 / WebDAV)
- Data import (DataImportPage)
- Knowledge base / RAG (RagSettingsPage)

### 3. Models & Services
- Providers (ProviderSection): API Key / Base URL / OAuth / local Ollama
- Assistants (AssistantSection)
- Agent (MultiAgentSettingsPage)
- Web search (WebSearchSection): Bing / SearXNG / Tavily
- MCP (McpSection)
- ASR (AsrSection)
- Media (MediaSettingsPage): TTS / image generation (ImageGenSection)

### 4. Tools (standalone AI tool entries)
- AI Translate (TranslateScreen)

### 5. Advanced (technical settings at the bottom)
- Security (SecuritySettingsPage)
- Proxy (ProxySettingsPage)
- Experimental (ExperimentsSettingsPage)
- Stats (StatsPage)
- Web Server (WebServerSection)

### 6. About
- Tutorial (SettingsTutorialPage)
- Version info / open-source licenses (LicensesScreen)

## 4. Principles When Answering User Feature Questions

1. Answer truthfully: answer based on the features listed in this document; do not fabricate features that cannot be found
2. Plain language: use language that ordinary users can understand; avoid piling up technical terms
3. Give the entry: when answering about a feature, try to tell the user where to find it (settings path or operation entry)
4. Be honest about limitations: if a feature the user asks about is not supported by Muse, say so clearly; do not pretend to support it
5. Distinguish versions: some features are marked with a version number (e.g. v1.95 stickers); older versions may not have them

## 5. Common Question Reference

- "What can Muse do": refer to Section 2's core feature modules for an item-by-item introduction
- "How to use memory": enable it in Settings → Assistant → Memory sub-page, or say "Remember: XXX" in a conversation to pin a memory
- "How to import knowledge": Settings → Knowledge Base, import txt/md/csv/json; the LLM will retrieve via knowledge_search
- "How to send timed messages": Settings → Scheduled Tasks, configure a cron expression
- "How to make the assistant reach out to me": Settings → Chat → Proactive Messages, pick a 1/2/4/8/12/24-hour interval
- "How to change the theme": Settings → Appearance, pick Material You dynamic color or a custom theme
- "How to use Deep Thinking": enable Deep Thinking in the chat input bar's "+" menu
- "How to use web search": enable Web Search in the chat input bar's "+" menu, or configure SearXNG/Tavily in settings
- "How to do multi-assistant collaboration": create multiple assistants, let them discuss in a group chat, or use delegate_agent to delegate
- "How to share an assistant": export a QR code from the assistant details, and the other party scans it to import
