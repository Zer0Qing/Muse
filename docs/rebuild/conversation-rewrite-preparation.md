# Conversation Rewrite Preparation

- Code commit: `3a6e113`
- Version: `1.0.79 / 179`
- MuseDb/FactDb: `95 / 13`
- Branch: `codex/overnight-rebuild-2026-08-20`

## Implemented

- Added Room 92→95 additive chain for turns, events, tool rounds, branch heads, message parts, `commitSeq`, and `parentMessageId`.
- Added `StreamingTurnBuffer`, redacted shadow events, `MessageProjection`, `ConversationProjector`, `MessageCommit`, and default-off rebuild flags.
- Kept `ChatViewModel` and legacy message fields as compatibility paths.
- Added soft-delete cleanup for outbox/checkpoints/rebuild artifacts and complete fork ID/group/parent remapping.
- Replaced the message action Boolean combination with one mutually-exclusive `MessageActionSurface` state.

## Validation

- Full JVM/Robolectric suite: app 798, memory 144, ai 215, common 30, accessibility 3; all passed.
- Engineering discipline diff check: passed with 0 errors and 0 warnings.
- `assembleDebug`: passed.
- Release Candidate: passed with `assembleRelease "-PversionName=1.0.79" "-PversionCode=179"`.

## Risks and rollback

- Shadow/new commit flags remain off by default.
- Live provider replay, MCP live run, real-device long session and backup import were not executed here.
- Detekt remains red with existing and newly surfaced complexity/style findings; see release acceptance report.
- Roll back code by disabling flags or reverting to commit `a9c0c0b`; roll back database by restoring the pre-migration `.bak`.
