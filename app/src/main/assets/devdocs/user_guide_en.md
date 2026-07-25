<!-- devdoc: internal dev doc, not shown to users, LLM queries via knowledge_search -->
# Muse User Guide

Reference this doc when users ask "how to use", "user guide", "help", "getting started", "tutorial", "what is this feature", "how to operate". Answer in plain language, avoid technical jargon, and give examples when necessary. All feature entries are based on real code; do not fabricate entries that cannot be found.

---

## 1. What is Muse

Muse is an Android AI assistant app. Its core positioning is a "companion LLM client + Agent platform".

The biggest differences from a typical AI chat web app:

- It has memory. Muse can extract and compile facts mentioned in conversations into summaries, and inject them automatically in the next conversation, so it "remembers" what you said before.
- It can take action. Beyond chatting, it can read and write files in the app sandbox, search the web, set alarms, send SMS, query the calendar, open apps, read the clipboard, etc.
- It can have multiple personas. You can create multiple assistants, each with its own system prompt, model, tools, and memory.
- It is private. All data is stored locally on your phone and is not uploaded to third-party servers (except when calling the model provider you configured yourself).
- It can reach out to you. Once proactive messaging is configured, the assistant will send you messages on a schedule, like a WeChat friend.

---

## 2. Getting Started

### First Launch

When Muse launches for the first time (and no model provider is configured locally), a full-screen onboarding page is shown, in two steps:

1. Step 1 Feature introduction: swipe left and right through 5 feature cards (Chat, Assistant, Privacy, Web Search, Tools). The bottom has "Next" and "Skip".
2. Step 2 Personalized naming: fill in two input fields
   - Assistant name (what you want to call the assistant, e.g. "Xiao Miao" or "JARVIS")
   - User nickname (what you want the assistant to call you, e.g. "Xiao Ming" or "Boss")
   - These two names are written into the user profile and affect how the AI refers to itself and to you in conversations. Both can be left empty to skip.
   - Two buttons at the bottom: "Start Using" (enter the main UI directly) or "Add Model Provider" (go configure the API).

### Adding a Model Provider

Tap "Add Model Provider" on the onboarding page, or later go to Settings (设置) → Models & Services (模型与服务) → Providers (供应商). You can:

- Add manually: fill in API Key, Base URL, and Model ID.
- Add from presets: pick a common provider (OpenAI / Anthropic / Google / DeepSeek, etc.).
- Import via QR code: scan a QR code to quickly import a provider configuration.
- Import third-party client config: supports importing JSON exported by CherryStudio and Chatbox (format is auto-sniffed; only providers and models are imported, not conversation history).

After configuring at least one provider, return to the main UI to start chatting.

---

## 3. Chatting with the Assistant

### Basic Operations

- Send a message: type in the input box at the bottom and tap the send button.
- Stop a reply: while the assistant is replying, the send button becomes a stop button; tap it to interrupt.
- Long message collapse: overly long replies are auto-collapsed with a gradient overlay; tap to expand the full text.
- Message content supports Markdown rendering, code highlighting, math formulas, and tables.
- Rich media in assistant replies: SVG / HTML / chart code blocks are rendered into visual content (charts support bar, line, and pie charts).
- Artifact cards: when a reply contains reusable content such as code, documents, or JSON, a clickable artifact card is generated for easy viewing or copying.

### Deep Thinking

In the "+" menu on the left of the input bar, there is a "Deep Thinking" (深度思考) toggle. When enabled, that conversation uses high-intensity reasoning (an 8000-token thinking budget), suitable for complex problems. Note: this toggle only applies to the current conversation, is not persisted, and reverts to the assistant's default setting after switching sessions or restarting the app.

### Web Search

The "+" menu in the input bar also has a "Web Search" (联网搜索) toggle. When enabled, each question first searches the web for results as context. By default it uses Bing (scraping cn.bing.com, no API Key needed). SearXNG (self-hosted) and Tavily (requires Key) are also supported, switchable in Settings (设置) → Models & Services (模型与服务) → Web Search (Web 搜索) or Settings (设置) → Chat (聊天) → Default Search Engine (默认搜索引擎).

### Message Long-Press Menu

Long-press (or tap) any message to bring up the action menu:

- Copy: copy the full message text to the clipboard.
- Regenerate: ask the assistant to answer this message again (for assistant messages).
- Delete: delete this message (useful for retracting a mis-sent message; a confirmation dialog appears).
- Edit: edit the user message content and resend.
- Quote: reply quoting this message's content (a quote block is shown at the top of the message).
- Translate: translate the message into a target language (tapping "Translate" brings up a language submenu to pick the target language).
- Read aloud: read this message aloud with TTS (only available for assistant messages).
- Favorite: add this message to favorites.
- Share: share the message content to other apps.

The last assistant message also shows quick "Copy" and "Regenerate" buttons, so you can operate without opening the menu.

---

## 4. Assistant Management

### Multiple Assistants

Muse supports creating multiple assistants, each an independent individual with its own name, avatar, system prompt, model, tools, memory, and conversation history. A "Default Assistant" is included by default.

Entry: Settings (设置) → Assistant (助手). Here you can create, switch, and delete assistants.

### Five Sub-Pages of Assistant Details

Go to Settings (设置) → Assistant (助手) → select an assistant, and you will see 5 sub-pages:

1. Basic: name, sort order, emoji avatar or image avatar, chat background image and opacity.
2. Prompt: system prompt (role setting), message template (supports {{variable}} placeholders), preset messages (fixed context injected before the chat starts).
3. Extensions: enabled local tools, MCP servers, Skills, Lorebook, quick messages, Prompt injection, custom request headers / request body.
4. Memory: whether to inject long-term memory, whether to use global memory, whether to inject recent session summaries, whether to enable time reminders.
5. Advanced: model ID, temperature, top-p, max tokens, context message count, reasoning level, whether to stream output.

Each assistant is configured independently and does not affect others. After switching assistants, you see the corresponding assistant's conversations and settings.

---

## 5. Memory System

Muse's memory is multi-layered:

### Long-Term Memory

During a conversation, the system extracts Facts from historical messages, stores them in the database, and compiles them into a markdown summary. In the next conversation, this summary is injected into the system prompt, so the assistant "remembers" what you said before.

Key points (must be honest with users):

- Long-term memory is injected as a "compiled summary", not as individual facts. What the assistant sees is the summary text.
- Extraction happens at the daily pipeline time (usually you have to wait a few hours), not in real time. A fact you just mentioned may not have been remembered yet.
- The memory toggle is in Assistant Details → Memory sub-page (on by default).
- The summary length (token budget) can be adjusted in Settings (设置) → Memory (记忆).

### Pinned Memories

Pin "must-remember" key information so it does not depend on the daily pipeline and is injected in every conversation.

- In a conversation, simply say "Remember: I don't drink coffee", and the assistant will pin it for you using the pin_memory tool.
- You can also add it manually in Settings (设置) → Assistant (助手) → Memory page.
- Pinned memories are stored in a local file, with size and entry-count limits for protection.

Entry: Settings (设置) → Memory (记忆), or Settings (设置) → Assistant (助手) → Memory sub-page.

---

## 6. Knowledge Base

Users can import documents into the knowledge base, and the assistant retrieves them by title and content via the knowledge_search tool.

- Supported formats: txt / md / csv / json.
- Entry: Settings (设置) → Knowledge Base (知识库).
- After import, the assistant automatically retrieves relevant documents as reference when answering.
- Note: entries with fileType="devdoc" in the knowledge base are internal development documents (this document is one), only for LLM queries and not shown in the knowledge base UI list.

---

## 7. Scheduled Tasks

Let the assistant execute tasks on a cron schedule, even if you do not open the app.

- Entry: Settings (设置) → Scheduled Tasks (定时任务).
- Configure the trigger time with a standard cron expression (minute hour day month weekday).
- At the scheduled time, the assistant generates a message and pops a notification.
- Existing tasks can be enabled / disabled / deleted.

---

## 8. Proactive Messaging

The assistant will proactively send you messages on a set interval and pop notifications, like a WeChat friend.

- Entry: Settings (设置) → Chat (聊天) → Proactive Messages (主动消息).
- Configuration: toggle + send interval (1 / 2 / 4 / 8 / 12 / 24 hours).
- At the scheduled time, a message is generated using the default assistant + the first session's context, inserted into the session, and a high-priority notification is shown (title "X has a new message").
- Style: like a real person sending a WeChat message, short plain text, with no prefix.

Common reasons for not receiving proactive messages: the toggle is off / the interval has not been reached / the default assistant or session is empty / notification permission is not granted.

---

## 9. Group Chat (Multi-Agent Collaboration)

Let multiple assistants collaborate and discuss in one group.

- Entry: Settings (设置) → Agent.
- Create a group chat and select the participating assistants.
- In the group chat, each assistant speaks according to its own persona and expertise, and can read other members' messages.
- If an assistant feels the current topic does not need its input, it can skip this round.
- Suitable for scenarios that need different perspectives (e.g. having one assistant good at writing and one good at analysis discuss the same thing).

---

## 10. Standalone Translate Page

Muse has a standalone full-screen translate page (iOS style).

- Entry: Settings (设置) → Tools (工具) → AI Translate (AI 翻译).
- Layout: select the target language at the top, input box in the middle (with paste / clear buttons), translation result shown below (selectable for copying), floating button at the bottom right to start or cancel translation.
- A progress bar is shown during translation.
- You can also select "Translate" in the message long-press menu to translate a single message (a language submenu will pop up).

---

## 11. Themes and Personalization

### Theme

Entry: Settings (设置) → Appearance (外观).

The theme system has three layers of priority (from high to low):

1. Dynamic color (Material You): Android 12+ follows the system wallpaper to pick colors (off by default, needs to be turned on manually).
2. Custom theme: generates a full color scheme from a seed color using the HCT algorithm (see below).
3. Preset theme: 6 hand-tuned color schemes (warm_paper, sakura, ocean, spring, autumn, amoled).

Theme mode (Follow system / Light / Dark) and font size tier (Small / Medium / Large / Extra large) are also adjusted here.

### Custom Theme

In Settings (设置) → Appearance (外观) you can create a custom theme:

- Pick 1~3 seed colors (primary is required, secondary / tertiary are optional).
- The system auto-derives a full color scheme using Material Color Utilities' HCT color space (the same algorithm as Android 12+ wallpaper dynamic color).
- You only need to pick the seed color; you do not need to understand the 50+ color fields.

### User Profile

Entry: Settings (设置) → Models & Services (模型与服务) → User Profile (用户画像).

You can fill in: assistant name, user nickname, age, city, MBTI, occupation, interests. This information is injected into the system prompt so the assistant knows you better and addresses you the way you like.

---

## 12. Backup and Import

### Backup

Entry: Settings (设置) → Backup (备份) (or Settings (设置) → Data & Backup (数据与备份)).

- Local backup: export / import app data to a local file.
- Cloud backup: supports S3 and WebDAV, with balance query.
- Backup contents include sessions, messages, assistants, settings, etc.

### Importing Third-Party Client Configurations

In the provider settings, you can import JSON exported by CherryStudio and Chatbox:

- Format is auto-sniffed; you do not need to select the source manually.
- Only providers and models are imported (API Key, Base URL, model list).
- Conversation history is not imported (incompatible format).
- Advanced configurations such as assistants and quick messages are not imported.
- On id conflict, the entry is skipped and existing configurations are not overwritten.

---

## 13. Web Server

Muse embeds a Ktor server that supports remote invocation of app capabilities within the local network.

- Entry: Settings (设置) → Web Server (Web 服务器) (or Settings (设置) → Data & Backup (数据与备份) → Web Server (Web 服务器)).
- Once enabled, devices on the same local network can invoke it remotely via HTTP + JWT.
- Combined with mDNS service discovery, it auto-broadcasts on the local network.
- Suitable for scenarios where you invoke phone-side assistant capabilities from a computer.

---

## 14. Practical Tips

### Let the Assistant Remember Information

In a conversation, simply say "Remember: my birthday is May 3", and the assistant will pin it using the pin_memory tool, so it remembers it in the next conversation too. It does not depend on the daily pipeline and takes effect immediately.

If you only mention something in passing, it may take a few hours for the daily pipeline to run before it is remembered. For important information, explicitly say "Remember".

### Use Deep Thinking for Complex Problems

When you encounter a problem that needs reasoning (math, logic, plan comparison), turn on "Deep Thinking" in the input bar's "+" menu, and the assistant will spend more thinking budget to analyze deeply. For casual chat, do not turn it on; it will be slower.

### Translation Tips

For short sentence translation, use "Translate" in the message long-press menu. For translating a whole paragraph of text, go to Settings (设置) → Tools (工具) → AI Translate (AI 翻译), which has a standalone full-screen page that supports paste, clear, and copy result.

### Stickers and Rich Media

The assistant can output SVG graphics, HTML snippets, and charts (bar / line / pie). When you need to visualize data, just say "draw a chart" or "show it with a chart".

### Custom Theme

If you are not satisfied with the preset themes, go to Settings (设置) → Appearance (外观) to create a custom theme, pick a seed color you like, and the system will auto-derive a full color scheme.

### Let the Assistant Use Your Preferred Nickname

Go to Settings (设置) → Models & Services (模型与服务) → User Profile (用户画像), fill in "User nickname" (e.g. "Boss") and "Assistant name" (e.g. "Xiao Miao"), and the assistant will refer to itself and to you that way in conversations.

### Division of Labor Among Multiple Assistants

Create multiple assistants, each with its own role (one for translation, one for coding, one for chatting), and switch between them in Settings (设置) → Assistant (助手). You can also create a group chat to let them collaborate.

### Save Data

For casual chat, do not turn on Web Search; only turn it on when you need real-time information (news, weather, stock prices). The same applies to Deep Thinking; enable it on demand.

---

## 15. FAQ

### The assistant does not remember what I said before?

Check whether the memory toggle in Assistant Details → Memory sub-page is on. Long-term memory is a compiled summary, not individual facts, and the daily pipeline takes a few hours to run. For important information, it is recommended to say "Remember: XXX" to pin it with pin_memory, or add it manually in Settings (设置) → Assistant (助手) → Memory page.

### Web search returns no results?

Check the search engine configuration in Settings (设置) → Models & Services (模型与服务) → Web Search (Web 搜索). The default Bing does not need a Key, but if the network is restricted, scraping may fail. You can switch to SearXNG (self-hosted) or Tavily (requires Key). Also confirm that the "Web Search" toggle in the input bar's "+" menu is on.

### Translation fails?

The standalone translate page requires a configured model provider to work. Check that there is at least one available provider in Settings (设置) → Models & Services (模型与服务). The same applies to "Translate" in the message long-press menu. If the model itself does not support it, switch to a model that does.

### The theme is not applied?

Check the theme selection in Settings (设置) → Appearance (外观). If dynamic color (Android 12+) is on, the color scheme only changes when the system wallpaper changes. A custom theme must be created first and then selected. Theme mode (Follow system / Light / Dark) controls light/dark separately.

### No proactive messages received?

Check whether the toggle in Settings (设置) → Chat (聊天) → Proactive Messages (主动消息) is on, whether the interval has been reached, and whether notification permission is granted. You also need at least one session and a default assistant, otherwise there is no context to generate a message.

### How to view the onboarding page again?

The onboarding page is only shown on first launch (when there is no provider). To view it again, go to Settings (设置) → About (关于) → Show onboarding again (重新显示引导). To change your nickname, go to Settings (设置) → Models & Services (模型与服务) → User Profile (用户画像).

### Deep Thinking is on but nothing changes?

Deep Thinking requires the current model to support reasoning. Some models do not support high-intensity reasoning. Also, Deep Thinking only applies to the current conversation, is not persisted, and reverts to the assistant's default setting in the next conversation.

### How to import configurations from other apps?

In the provider settings, import JSON exported by CherryStudio or Chatbox. The format is auto-sniffed, and only providers and models are imported. Conversation history and advanced configurations are not imported.
