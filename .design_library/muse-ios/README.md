# Muse iOS Design System

A design system reconstruction of **Muse** — an AI companion app with chat, streaming, thinking process, tool calls, approval, memory, RAG, image/video generation, voice, and agent planning. The system is purpose-built for a mobile-first, Chinese-speaking audience who expects both iOS-native polish and the warmth of a literati companion.

## What this design system covers

- **Foundations** — color (10-step scales, warm neutrals, emotional accents), typography (system sans + Noto Serif SC + mono), spacing (4px base, 9 steps), radius (iOS progressive rounding), shadow (5-level whisper-quiet), frosted glass materials, motion (spring easing)
- **Components** — 10 components covering all Muse AI companion surfaces (message-bubble, input-bar, conversation-list, thinking-card, tool-call-card, navigation-bar, approval-card, agent-plan-card, media-card, settings-page)
- **Sample kit** — `app` type UI kit with click-through recreation

### Key design decision

iOS Design Language adapted for Muse — preserving 月桂绿 (Laurel Green) brand identity, 文人 (literati) warmth, and 陪伴 (companionship) tone while adopting Apple's large title navigation, frosted glass materials, progressive rounding, and subtle spring motion. The canvas is 90% neutral; brand green occupies less than 5% of visible surface, reserved exclusively for interactive elements.

---

## Content Fundamentals

### Voice & tone

Muse speaks with a companionable, literati warmth. Chinese is the primary UI language — every surface label, button, and system message is Chinese-first. The tone avoids corporate coldness; instead it carries the quiet refinement of a 文人 companion. No emoji appear on any UI surface; warmth comes from typography choices (Noto Serif SC for AI replies) and color temperature (warm-tuned neutrals at `#FAFAF8`, never cold pure grays) rather than decorative embellishment. AI responses use serif typography to evoke a literati quality, creating a reading experience that feels like conversing with a thoughtful scholar rather than querying a machine.

### Concrete copy examples

- Chat tab label: *对话*
- Memory feature: *记忆*
- Knowledge base: *知识库*
- Settings: *设置*
- Send action: *发送*
- Streaming state: *思考中*
- Tool invocation: *工具调用*
- Human-in-the-loop: *审批*
- Stop generation: *停止生成*
- New conversation: *新建会话*
- Sidebar header: *会话列表*
- Workspace: *工作区*

### When generating copy

- Chinese is always the primary language. All button labels, section titles, and system states must be Chinese.
- Brand green is scarce — use it only for interactive elements (send buttons, active states, primary CTAs). Never for decorative fills.
- Warm-tuned neutrals over cold pure grays. The neutral palette carries a slight warmth (`#FAFAF8`, not `#FFFFFF`) to maintain companionable tone.
- AI reply content uses Noto Serif SC; UI chrome (navigation, labels, buttons) uses the system sans stack. This typographic duality is the core of the literati aesthetic.

---

## Visual Foundations

### Color

The entire color architecture rests on a 90% neutral canvas philosophy. The light-mode background is warm white `#FAFAF8` — deliberately off-white to carry companionable warmth, never the cold `#FFFFFF` of a generic productivity app. In dark mode, the canvas drops to OLED pure black `#000000` for battery savings on AMOLED displays, with surface elevation achieved through progressive lighter grays (`#1C1C1E` for elevated surfaces, `#2C2C2E` for containers).

The brand primary is Laurel Green (月桂绿) `#2A7A55` — a bamboo/cyan-leaning green that anchors the entire identity. It carries a full 10-step scale from the palest tint `#E6F2EC` (primary-50) through the brand anchor `#2A7A55` (primary-500) to the deepest forest `#0F3D2A` (primary-900). In dark mode, the primary shifts upward to `#4A9F70` (primary-500 dark) for sufficient contrast against OLED black. This green is intentionally scarce: it occupies less than 5% of visible surface area, reserved exclusively for interactive elements — the send button, active tab indicators, primary CTAs, and focused input borders. Large-area green fills are forbidden.

The neutral scale runs 10 stops from `#FAFAF8` through `#1A1A1A`, with the dominant working neutrals being `#FAFAF8` (canvas), `#F2F2F0` (container-low), `#E8E8E4` (container-high and divider), `#8E8E93` (secondary text), and `#1A1A1A` (primary text). Text ink is `#1A1A1A` in light mode, inverting to `#E8E8E8` in dark mode.

Four semantic scales each carry 10 stops: success anchors at `#3E9C5E` (a leaf-green that harmonizes with Laurel Green), warning at `#FF9500` (iOS amber-orange), danger at `#D94034` (a warm coral-red, never harsh), and info at `#007AFF` (Apple's system blue, used sparingly). An emotional accent palette provides personality beyond semantics: coral `#FF6B6B` for warmth, lavender `#9B8EC4` for calm, amber `#E8A838` for attention, and sage `#7DB88F` as a softer green cousin. These accents are desaturated in dark mode (coral to `#E86B6B`, lavender to `#8E82B4`, sage to `#6FA882`) to reduce visual energy against the OLED canvas.

### Typography

Three font families define the typographic system, each with a distinct role. The primary sans stack is `-apple-system, 'SF Pro Display', system-ui, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei'` for display and heading text, shifting to `'SF Pro Text'` in the body stack — on Apple devices these render SF Pro Display and SF Pro Text respectively; on Android the `system-ui` keyword resolves to the system sans (typically Roboto or Noto Sans SC); on Windows to Segoe UI or Microsoft YaHei. This stack serves all UI chrome: navigation titles, button labels, input text, captions, and tab labels. No paid fonts are imported.

The serif family is **Noto Serif SC** (with `Songti SC` and `Georgia` as fallbacks) — a free Google Font that provides the 文人 (literati) quality for AI reply content and empty-state headings. This is the typographic signature of the system: when the AI speaks, the text shifts to serif, creating a visual duality between UI chrome (sans) and AI voice (serif). It is loaded via Google Fonts CDN with `font-display: swap` to avoid render-blocking.

The mono stack is `'SF Mono', 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo` — used for code blocks, tool-call parameters and results, thinking-process content, and any monospaced data display.

The type scale spans seven working sizes: display-xl at 34px Bold with 1.2 line-height (the iOS large title), display-l at 28px, title-l at 20px Semibold with 1.3 line-height, body-l at 16px Regular, body-m at 14px Regular, caption at 13px, label at 12px Medium, and tab at 11px Medium. The 1.6x line-height for body text (25.6px computed at 16px) is deliberate — it provides generous reading comfort for long-form AI replies, which are the primary content surface. Display text carries a -0.02em letter-spacing for optical tightening at large sizes, while labels and tabs use +0.02em for legibility at small sizes. Weights span four tiers: regular 400, medium 500, semibold 600, bold 700.

### Spacing

The spacing system is built on a 4px base unit with a 9-step scale: 4, 8, 12, 16, 20, 24, 32, 48, and 64px (`--space-1` through `--space-9`). The 20px step serves double duty as the message gap between conversation bubbles — the iOS "breathing space" that prevents messages from feeling cramped. Screen edge padding is 16px, matching iOS safe-area conventions. Touch targets are 48px (`--size-touch-target`), buttons range from 32px (small) through 40px (medium) to 48px (large), and the input bar minimum height is 44px (`--size-input`), aligning with iOS Human Interface Guidelines.

### Radius

Radius follows iOS progressive rounding — each component tier gets its own value, creating a visual rhythm from small to large. Badges use 4px (`--radius-tiny`), tags use 8px (`--radius-small`), buttons use 12px (`--radius-button`), chat bubbles use 18px (`--radius-bubble`), cards use 20px (`--radius-card`), bottom sheets use 24px (`--radius-sheet`), floating action elements use 28px (`--radius-mega`), and fully rounded elements use the 9999px pill (`--radius-pill`). The signature asymmetric bubble tail is an iOS-native pattern: user bubbles carry a 6px radius on the trailing bottom-right corner (against 20px card radius on the other three corners), while AI bubbles carry 6px on the trailing bottom-left corner. This asymmetry visually distinguishes speaker roles without color dependency.

### Shadow / Elevation

Five levels of whisper-quiet elevation, each progressively heavier. In light mode, shadows use warm-tinted alpha black: level 1 is a hairline `0 0.5px 0 rgba(0,0,0,0.04)` for subtle border separation, level 2 is `0 1px 3px rgba(0,0,0,0.06)` for card subtlety, level 3 is `0 2px 8px rgba(0,0,0,0.08)` for elevated cards, level 4 is `0 8px 24px rgba(0,0,0,0.10)` for modals and sheets, and level 5 is `0 16px 48px rgba(0,0,0,0.14)` for ambient depth. The philosophy is quiet: shadows exist to separate layers, not to draw attention. In dark mode, shadows shift to pure black alpha with heavier values (0.20, 0.30, 0.40, 0.50) because depth perception against OLED black requires stronger contrast. The shadow-1 hairline inverts to `rgba(255,255,255,0.04)` for visibility on black.

### Frosted Glass

iOS material design manifests through frosted glass on three primary surfaces: the tab bar, navigation bar, and input bar. The standard glass treatment uses 72% opacity background (`rgba(252,250,248,0.72)` in light, `rgba(28,28,30,0.72)` in dark), 20px backdrop blur, and 180% saturation boost. This creates the translucent layering effect where content scrolls beneath frosted UI elements. A thin variant exists for less prominent surfaces at 50% opacity and 12px blur. The glass effect requires `backdrop-filter` support (iOS Safari native, Android API 31+ with render effect).

### Borders

Hairline borders at 0.5px define subtle dividers and card edges — the light-mode hairline is `rgba(0,0,0,0.06)`, inverting to `rgba(255,255,255,0.08)` in dark mode. Card outlines use 1px solid `#E8E8E4` (light) or `#2C2C2E` (dark) for the divider/rule token. Focus states adopt accent-tinted borders, shifting border color to the brand primary green to signal active interaction without heavy outlines.

### Motion

Motion follows a three-tier duration system: 150ms for fast micro-interactions, 300ms for default transitions, and 500ms for slow reveals. The primary easing curve is a spring approximation: `cubic-bezier(0.22, 0.68, 0, 1)` — an overshoot-free spring that feels natural without bouncing. A secondary ease-out curve `cubic-bezier(0.16, 1, 0.3, 1)` handles deceleration-into-rest patterns. Under `prefers-reduced-motion`, all spring animations disable to instant transitions.

---

## Component Patterns

| Component | File | Key Insight |
|---|---|---|
| message-bubble | `components/message-bubble.json` | Asymmetric tail radius (6px) distinguishes user vs AI; AI bubbles use Noto Serif SC |
| input-bar | `components/input-bar.json` | Frosted glass floating composer with expandable tool and send/stop states |
| conversation-list | `components/conversation-list.json` | iOS large title grouped list with swipe actions and empty-state serif heading |
| thinking-card | `components/thinking-card.json` | Collapsible reasoning with streaming shimmer gradient animation |
| tool-call-card | `components/tool-call-card.json` | Status-driven card with mono parameter and result sections |
| navigation-bar | `components/navigation-bar.json` | Frosted glass tab bar with large title top navigation |
| approval-card | `components/approval-card.json` | Risk-level-driven approval card with color-coded urgency |
| agent-plan-card | `components/agent-plan-card.json` | Vertical timeline stepper with per-step status tracking |
| media-card | `components/media-card.json` | Unified image/video card with generating shimmer and ready states |
| settings-page | `components/settings-page.json` | iOS grouped table view settings with profile header and sectioned rows |

---

## Index

- `README.md` — this brand narrative document
- `colors_and_type.css` — runtime CSS variables for color, type, spacing, radius, shadow, glass, motion
- `css.json` — structured JSON token representation for programmatic consumption
- `components.css` — aggregated component CSS auto-extracted from preview pages
- `components/index.json` — component index with cross-component patterns
- `components/{slug}.json` — per-component contract JSON (10 components)
- `preview/component-{slug}.html` — one HTML preview per component
- `SKILL.md` — agent skill manifest with quick-map and essentials

---

## Caveats / known substitutions

1. **SF Pro** is Apple proprietary — the system font stack (`-apple-system`) renders SF Pro on Apple devices but falls back to Roboto on Android and Segoe UI / Microsoft YaHei on Windows. No paid fonts are imported; this is by design for cross-platform compatibility.
2. **Noto Serif SC** adds approximately 2MB of font weight on web; loaded via Google Fonts CDN with `font-display: swap` so text renders immediately in fallback serif and swaps once loaded. On Apple devices, `Songti SC` serves as a local fallback.
3. **Frosted glass** (`backdrop-filter`) requires Android API 31+ (`Modifier.blur` with render effect). Pre-API-31 Android fallback: solid surface color with slight transparency, losing the blur but maintaining translucent layering.
4. **iOS large title collapse** animation uses native `UIScrollView` on iOS; on Android it is replicated via `LazyColumn` + `stickyHeader` with a custom scroll listener; on Web via `IntersectionObserver`. Behavior parity is approximate, not pixel-exact.
5. **Asymmetric bubble tail** (6px trailing corner) is an iOS signature pattern. Android Material 3 prefers pill-shaped bubbles; the tail is optional on Android and may be omitted for platform consistency.
6. **Dark mode** uses OLED pure black `#000000` for battery savings — surface elevation is achieved through lighter grays (`#1C1C1E`, `#2C2C2E`) rather than Apple's default dark base. This is a deliberate departure.
7. **1.6x line-height** is calibrated for long-form AI reply reading comfort; it may feel loose for short UI labels. Use 1.3x for titles and 1.2x for large display text.
8. **Thin glass variant** (50% opacity, 12px blur) is referenced in component usage but not yet defined as a separate token in `colors_and_type.css` — it is applied inline where needed.
9. **`prefers-reduced-motion`**: all spring animations (`cubic-bezier(0.22, 0.68, 0, 1)`) disable to instant transitions. Shimmer and blink animations also halt.
