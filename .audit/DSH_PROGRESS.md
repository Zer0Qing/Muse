# Muse audit fix execution progress

## Completed
- Read `.audit/REPORT_MASTER.md` and all `REPORT_*.md`; verified already-fixed history: `764823a2` (backup folders), `92c2d1fa` (A2A headless isolation), `54c8224f` (Tasker enable/rate gate), `3d2cdedc` (website credential documentation cleanup), `fe691617` (English string + CJK baseline).
- CI F1 gate fixed and committed as `df740a6d fix(ci): handle multiline logger CJK and locale check AUD-BUILD-CI-F1`.
  - Root cause review: KDoc block comments were already stripped correctly. The two matches were a multiline `Logger.i(...)` diagnostic (now stripped across lines) and a real OAuth hard-coded runtime message (converted to resource).
  - Added scanner regressions; added the OAuth resource to all seven string sets.
  - Passed `python ci/test/test_hardcoded_cjk.py`, `python ci/script/check_hardcoded_cjk.py` (490 baseline-count findings, no increases), `python ci/script/check_localizations.py --strict-locales en` (en 0 missing), and targeted ktlint format.
- AI F1 supersede code change is ready but NOT YET COMMITTED.
  - On replacement, reset UI/persistence/notification/token cursors to the replacement length, reset persist/notification/token timestamps so short replacement text is reported promptly, then force `throttledPersist()`.
  - Added `ChatStreamFinalizationOrderTest` source contract regression.
  - Passed targeted `:app:testDebugUnitTest --tests io.zer0.muse.ui.ChatStreamFinalizationOrderTest` twice and targeted ktlint format.
  - Preserved `ChatToolLoopRunner.kt` CRLF to avoid whole-file line-ending churn; verify with `git -c core.whitespace=cr-at-eol diff --check`.
  - Next: precise `git add` two files and commit `fix(ai): reset resume supersede cursors AUD-AI-F1`, then update progress.
- Initial worktree had untracked `.audit/` and `app/config/`; only this progress note is intentionally written under .audit; never stage unrelated files.

## Remaining, in requested batch order
1. Commit AI F1 above.
2. X1 vdserver: add bounded shell command wait and force-destroy timeout processes; test/build and commit.
3. UI F2: CAS accessor message updates at ChatToolResumeCoordinator:194/235. UI F3: guard `refreshDisplayedSessionFromDatabase` with SessionSwitchGuard and CAS. Verify/commit separately.
4. RAG F1 compare built-in doc embedding key; RAG F3 stabilize dimension fingerprint after guessed→actual dimension; tests/commits.
5. Channel F2 receiver self-heal: empty config retry, corrupted Telegram offset tolerance, assess WorkManager fallback; tests/commit.
6. Data F1 B-34 reconcile actual inserted counts, not just parsed row counts; test/commit.
7. CI F3 tag↔Gradle version; CI F5 detekt cap headroom/ledger; CI F7 Kotlin line endings; NOTICE v2.5.x dependencies. Each verify and commit.
8. Final `:app:ktlintKotlinSourceCheck :app:detekt :app:testDebugUnitTest`; report all outcomes and residuals.

## Constraints
- `.audit/` reports stay read-only; this progress file is the only allowed note. Do not touch releases. Do not build APK, release, push, or tag. Precise staging and one commit per independent fix.
- X1 vdserver completed: bounded both shell waits to 10s, force-destroyed timed-out processes, and returned empty/null failure results; synchronized full-lock optimization deferred to a later task. `:virtual-display-server:compileDebugKotlin` passed. Commit: `fix(vdserver): bound shell command wait AUD-X1`.
