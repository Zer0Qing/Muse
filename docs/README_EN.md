<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="../app/src/main/res/drawable/ic_muse_logo.png">
    <img src="../app/src/main/res/drawable/ic_muse_logo.png" width="96" height="96" alt="Muse">
  </picture>
</p>

<h1 align="center">Muse</h1>

<p align="center">
  <b>Your AI Companion That Remembers</b><br>
  <i>4‑tier memory · Multi‑model · Offline‑first · Extensible</i>
</p>

<p align="center">
  <b>English</b> · <a href="README.md">中文</a>
</p>

<p align="center">
  <a href="../LICENSE"><img src="https://img.shields.io/badge/license-GPLv3-blue.svg" alt="License: GPL v3"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen" alt="Min SDK">
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple" alt="Kotlin">
  <img src="https://img.shields.io/badge/Compose-Material%203-ff69b4" alt="Compose">
  <img src="https://img.shields.io/badge/build-passing-success" alt="Build">
  <img src="https://img.shields.io/badge/version-1.0.0-blue" alt="Version">
</p>

<p align="center">
  <a href="#-why-muse">Why Muse</a> ·
  <a href="#-features">Features</a> ·
  <a href="#-quick-start">Quick Start</a> ·
  <a href="#-architecture">Architecture</a> ·
  <a href="#-tech-stack">Tech Stack</a> ·
  <a href="#-privacy">Privacy</a> ·
  <a href="#-license">License</a>
</p>

---

## 💡 Why Muse?

Most AI apps treat every conversation like meeting a stranger — no memory, no continuity, no personality. **Muse is different.**

It builds a persistent relationship with you through a 4‑layer memory system that remembers your preferences, your history, and what matters to you. It supports your choice of models (OpenAI, Anthropic, Gemini, DeepSeek, or any OpenAI‑compatible provider). It speaks, searches the web, executes tools, and even proactively starts a conversation when it hasn't heard from you in a while.

**Everything works offline by default. No account required. No data leaves your device unless you explicitly enable it.**

---

## ✨ Features

### Core

<details open>
<summary><b>🧠 4‑Layer Memory</b> — remembers what matters, forgets what doesn't</summary>

```
Conversation → Fact Extraction → Rolling Summary
            → Compile & Aggregate → Deep Processing
```

- **Critical facts never decay**: medical info, financial details, core identity — importance ≥ 2 facts are protected from automatic decay
- **Trivial details fade naturally**: daily preferences and casual remarks age out over time
- **Every fact is traceable**: each memory shows its source session and when it was stored
- **You stay in control**: adjust importance, delete, or filter memories from the dedicated dashboard
</details>

<details>
<summary><b>🔌 Multi‑Provider</b> — pick your model, not your app</summary>

20+ pre‑configured providers across three categories:

| Category | Providers |
|----------|-----------|
| **Overseas** | OpenAI, Anthropic, Gemini, Groq, Together, Mistral, OpenRouter, DeepInfra, Fireworks |
| **Domestic** | DeepSeek, Qwen, GLM, Moonshot, Doubao, Baichuan, Lingyi, StepFun |
| **Relay** | OpenCode, API2D, AIHubMix, DeepBricks + custom templates |

Model IDs are **fetched dynamically** from each provider's `/models` endpoint — no hard‑coded lists, no stale entries.
</details>

<details>
<summary><b>🤖 Multi‑Agent Collaboration</b> — a team of assistants</summary>

Create multiple assistants with distinct personalities and expertise. Delegate tasks mid‑conversation:

- `@assistant` mention to delegate
- Visual task cards show delegation progress step by step
- Round‑robin execution for team tasks
</details>

<details>
<summary><b>🔧 Skill System + MCP</b> — infinitely extensible</summary>

- **30+ built‑in tools**: file I/O, web search, knowledge base, calendar, clipboard, calculator, SMS, alarms, stickers, image/video generation, and more
- **`.skill.json` import**: create and share custom skills with parameter schemas
- **MCP protocol**: connect to external MCP servers for dynamic tool extension (OAuth, SSE transport, auto‑discovery)
</details>

### Interaction

<details>
<summary><b>🎤 Streaming Speech Recognition</b> — talk naturally, see text in real time</summary>

- DashScope Paraformer, Step Whisper API, or Vosk offline Chinese recognition
- Partial results displayed as you speak
- Gesture interaction: long‑press to record, swipe up to cancel
- Real‑time amplitude waveform visualization
</details>

<details>
<summary><b>🖼️ Multi‑modal Input</b> — images, PDFs, and documents</summary>

- **OCR**: ML Kit offline Chinese text recognition, auto‑extracts text into conversation context
- **PDF parsing**: pdfbox‑android extracts text for full discussion
- **Auto‑detected formats**: TXT, DOCX, EPUB, and more
- **Image generation**: built‑in DALL‑E / Gemini support
</details>

<details>
<summary><b>🌐 Web Search</b> — real‑time information</summary>

- **Jina AI Reader**: free tier, returns clean Markdown summaries
- **Bing Web Search**: Jsoup‑powered structured result extraction
- **Custom API**: compatible with SearXNG, Tavily, or any endpoint
- **Graceful degradation**: falls back to search snippet on fetch failure
</details>

<details>
<summary><b>💬 Proactive Messaging</b> — Muse starts the conversation</summary>

Like a real companion, Muse can message you when it hasn't heard from you in a while:

- Smooth interval slider (continuous, no discrete steps)
- Time‑window control (default 8 AM – 10 PM), supports midnight‑crossing
- Agent‑only mode — never interrupts task workflows
</details>

<details>
<summary><b>🗣️ Text‑to‑Speech</b> — listen to replies</summary>

- System TTS for zero APK overhead
- Optional cloud TTS (OpenAI, MiniMax, Edge) for higher quality
- Audio routing: speaker, earpiece, or Bluetooth
- Speed, pitch, and language per‑assistant configuration
</details>

### Experience

<details>
<summary><b>🎨 6 Handcrafted Themes</b> — warm‑paper inspired</summary>

| Theme | Light | Dark |
|:------|:---:|:----:|
| Warm Paper (default) | ✅ | ✅ |
| Sakura | ✅ | ✅ |
| Ocean | ✅ | ✅ |
| Spring | ✅ | ✅ |
| Autumn | ✅ | ✅ |
| AMOLED | ✅ | ✅ |

Plus 8 color‑blind‑friendly curated palettes for custom themes. Every theme fully defines all Material 3 color roles.
</details>

<details>
<summary><b>📱 Markdown Rendering</b> — rich conversation display</summary>

- Syntax‑highlighted code blocks (20+ languages) with line numbers
- KaTeX math formulas
- Mermaid flowcharts (`securityLevel: 'strict'`)
- Auto‑detected plain‑text URLs with tap/long‑press gesture controls
- Link confirmation dialog prevents accidental opens
</details>

<details>
<summary><b>📦 Sticker Pack</b> — expressive reactions</summary>

- Import zip archives — auto‑classified by folder structure
- Probability‑based auto‑send during conversations (0–100% slider)
- Category filter chips + preview grid
- Long‑press to delete
</details>

<details>
<summary><b>🔒 Security First</b></summary>

- App PIN lock with exponential backoff (5 failures → 30s lockout)
- Sensitive config encrypted via Android Keystore (AES‑256‑GCM)
- Cloud backups encrypted with user‑provided password (PBKDF2 + AES‑256‑GCM)
- `allowBackup=false` — no ADB backup extraction
- WebView sanitizes LLM output — no `<iframe>`, `<form>`, or `javascript:` execution
- PIN verification blocks deep links to prevent escalation
</details>

### Platform

- **Built‑in update checker** via GitHub Releases API
- **Wear‑os‑style widgets** via Glance Compose
- **Embedded Web server** (Ktor + JWT + mDNS) for LAN API access
- **Config importer** from CherryStudio / Chatbox
- **Backup & restore** with local file + S3 / WebDAV cloud sync
- **Full‑text search** over conversation history (Room FTS)
- **Scheduled tasks** with WorkManager fallback
- **Usage statistics** with heatmap visualization
- **Crash handler** with safe mode — skip Koin init on crash loop, one‑tap log export

---

## 🏗️ Architecture

```
Muse
├── app/          # UI (Compose), ViewModels, platform services, data layer
├── ai/           # Provider abstraction (OpenAI, Anthropic, Gemini), image gen
├── memory/       # 4‑tier memory system (fact extraction, summarization, compilation)
├── common/       # Shared utilities (logging, JSON, coroutine helpers)
└── material3/    # Material 3 color utilities
```

### Key Decisions

- **Full‑screen pages over dialogs** — `ModalBottomSheet` freezes with nested scroll + IME
- **Model IDs fetched dynamically** — never hard‑coded, always pulled from `/models`
- **Custom `resultOf{}`** — correctly rethrows `CancellationException` (unlike stdlib `runCatching`)
- **Hot config cached in `@Volatile` fields** — changes take effect immediately, no restart needed
- **100% Kotlin** — zero Java files

---

## 🚀 Quick Start

### Prerequisites

- Android Studio Hedgehog (2024.1.1) or later
- JDK 17+
- Android SDK 35
- An API key from your preferred provider (OpenAI, Gemini, DeepSeek, etc.)

### Build & Install

```bash
git clone https://github.com/5352124/Muse.git
cd Muse

./gradlew :app:assembleDebug    # debug build
./gradlew :app:installDebug     # install on connected device
```

### First Run

1. Open the app → onboarding wizard introduces key features
2. Set your name and your assistant's name
3. Add an API provider (or use the pre‑configured templates)
4. Start chatting — Muse remembers everything from here

> **Tip**: Basic conversation works completely offline. Internet is only needed for provider APIs, web search, and cloud backup — all opt‑in.

---

## 📖 Tech Stack

| Category | Choice |
|----------|--------|
| **Language** | Kotlin 2.0 (100% Kotlin) |
| **UI** | Jetpack Compose + Material 3 |
| **Architecture** | Single‑Activity, MVVM, Koin DI |
| **Database** | Room (SQLite + FTS) + DataStore Preferences |
| **DI** | Koin |
| **Networking** | OkHttp (SSE), Ktor (embedded server) |
| **Images** | Coil (SVG + GIF) |
| **OCR** | ML Kit Chinese text recognition |
| **ASR** | DashScope Paraformer, Step Whisper, Vosk offline |
| **Serialization** | kotlinx.serialization |
| **Widgets** | Glance Compose |
| **PDF** | pdfbox-android |
| **HTML** | Jsoup |
| **TTS** | System TTS + OpenAI / MiniMax / Edge cloud TTS |

---

## 🔒 Privacy

Muse is built **offline‑first**:

- ✅ All conversations, memories, and knowledge stay on your device (Room database)
- ✅ No account required — no telemetry, no analytics, no data collection
- ✅ Internet features (web search, cloud ASR, cloud backup) are off by default
- ✅ App PIN lock prevents unauthorized access
- ✅ Backup encryption with your own password (PBKDF2 + AES‑256‑GCM)
- ✅ Crash logs are local only — exported manually via safe mode

---

## 📚 Documentation

- [Overview](01-overview.md) — positioning, core concepts, version info
- [Tech Stack](02-tech-stack.md) — full technology inventory
- [Project Structure](03-project-structure.md) — directory organization
- [Core Flows](04-core-flows.md) — chat flow / Transformer Pipeline
- [Maintenance Guide](09-maintenance-scenarios.md) — common scenarios / release process
- [Version History](12-version-history.md) — complete changelog
- [Known Issues](13-known-issues.md) — technical debt list
- [Development Standards](15-development-standards.md) — coding/build conventions

Full index: [_sidebar.md](_sidebar.md).

---

## 🤝 Contributing

- **Bug reports** → [Open an issue](https://github.com/5352124/Muse/issues)
- **Feature requests** → [Start a discussion](https://github.com/5352124/Muse/discussions)
- **Star the repo** → Helps others discover Muse

## 📄 License

Since v1.119, Muse is licensed under the **GNU General Public License v3**.

- The entire project (source code, resources, and derivative works) is GPL v3
- Third‑party dependencies each carry their own licenses — see [NOTICE](../NOTICE)

Full license text: [LICENSE](../LICENSE)
