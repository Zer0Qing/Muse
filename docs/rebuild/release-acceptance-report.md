# Release Acceptance Report

- Code commit: `2c5f6b2`
- Version: `1.0.79 / 179`
- MuseDb/FactDb: `95 / 13`
- Release APK: `E:\1Project\Muse\1muse\app\build\outputs\apk\release\app-universal-release.apk`
- APK SHA-256: `ab7b8d8494ecca915c8917736b9e191a4c900b477df5deb373aaa038ead3aae6`
- Certificate DN: `CN=Muse, OU=OpenSource, O=zer0, L=Shanghai, ST=Shanghai, C=CN`
- Certificate SHA-256: `e82f4ecde8304b7d78b530336a48e41a42b80c0ebac8af2d2c10448277644ebf`

## Acceptance results

| Gate | Result |
|---|---|
| `:app:compileDebugKotlin` | PASS |
| Full JVM/Robolectric tests | PASS: 1190/1190 |
| Engineering discipline diff check | PASS: 0 errors, 0 warnings |
| `assembleDebug` | PASS |
| `assembleRelease` with 1.0.79/179 | PASS |
| APK v2 signature verification | PASS |
| Release lint vital | PASS, no errors/warnings |
| detekt/ktlint full gate | BLOCKED: detekt reports 88 app + 36 memory findings |
| Real-device install/upgrade | NOT RUN |
| Live MCP/provider replay | NOT RUN |
| Backup import and physical restore | NOT RUN |

## Release policy

This is a local Release Candidate only. No remote push, publication, deployment, device installation or user database mutation was performed.

## Rollback

1. Keep legacy projection and ChatViewModel path active.
2. Set `ConversationRebuildFlagStore.current` to all-default-off.
3. Revert code to `a9c0c0b` if needed.
4. Restore the pre-migration database `.bak`; do not downgrade in place and do not use destructive migration.
