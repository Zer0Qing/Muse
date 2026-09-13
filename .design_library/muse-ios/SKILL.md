---
name: muse-ios-design
description: Use this skill to generate well-branded interfaces for Muse iOS. Contains colors, type, fonts, assets, and UI kit for prototyping mobile app UIs with iOS design language.
user-invocable: true
---
# Muse iOS Design Skill

Read the `README.md` file within this skill, and explore the other available files.

If creating visual artifacts, copy assets out and create static HTML files. If working on production code, read the rules here to become an expert in designing with this brand.

## Quick map
- `README.md` — brand context, content fundamentals, visual foundations (read first)
- `colors_and_type.css` — drop-in CSS variables for colors, type, radius, shadow, spacing
- `css.json` — structured token understanding source
- `components/index.json` — component index + cross-component patterns
- `components.css` — aggregated component CSS
- `preview/` — small HTML cards illustrating foundations and components
- `library-consumption.json` — recommended downstream read order

## Essentials at a glance
- Brand primary #2A7A55 — Muse Laurel Green (月桂绿), scarce interactive accent on 90% neutral canvas
- Radius 4/8/12/18/20/24/28/pill — iOS progressive rounding, bubble tail 6px asymmetric
- 40px default control height, 4px spacing unit, 8-pt grid, 20px message gap for iOS breathing
- Type: system font stack (-apple-system, system-ui, "PingFang SC"); Noto Serif SC (literati headings); SF Mono/JetBrains Mono (code)
- Voice: bilingual CN-first, companionable, literati warmth, no emoji in UI
- Shadows whisper-quiet: 5 levels from hairline border to 15px ambient, warm-tinted alpha
- AI components are first-class: dedicated thinking/streaming/tool-call/approval states
- Frosted glass materials: 72% opacity + 20px blur + 180% saturation for nav bars and input bars

## Components
| Slug | Name | Key Insight |
|------|------|-------------|
| message-bubble | Message Bubble | Asymmetric tail radius distinguishes user vs AI |
| input-bar | Input Bar | Frosted glass floating composer with expandable tools |
| conversation-list | Conversation List | iOS large title grouped list with swipe actions |
| thinking-card | Thinking Card | Collapsible reasoning with streaming shimmer |
| tool-call-card | Tool Call Card | Status-driven card with parameter and result sections |
| navigation-bar | Navigation Bar | Frosted glass tab bar with large title top nav |
| approval-card | Approval Card | Risk-level-driven approval card with color-coded urgency |
| agent-plan-card | Agent Plan Card | Vertical timeline stepper with per-step status tracking |
| media-card | Media Card | Unified image/video card with generating shimmer and ready states |
| settings-page | Settings Page | iOS grouped table view settings with profile header and sectioned rows |
