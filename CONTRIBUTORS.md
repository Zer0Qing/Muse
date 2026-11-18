# Contributors

Muse 的代码与文案来自这些人的贡献。

## 本地化

- **joselofarias-byte**（José Luis Farías）— 西班牙语本地化：Android 全量文案、Web 控制台文案，以及配套的字符串抽取与 4 个回归测试。合并于 PR #14。
- **PedroFlores199**（Pedro Flores）— 西班牙语校对：补齐 SenseVoice 本地语音识别 26 条文案（`asr_*`），修正 1107 条既有翻译——截断的补全、品牌与技术名误译（SOCKS5 / Whisper / Agnes 曾被当作普通词翻译）、按钮词纠正、敬语 usted 统一为 tú、重音符与 ñ 补回、全角标点改为 « »。合并于 PR #15。

---

贡献者姓名按贡献类型归档；提交历史为保持作者归属简洁，统一记在项目维护者名下，实际贡献记录见上表与对应 PR。

## 合并方式

所有外部 PR 一律以**压缩合并（squash）**进入 `main`：压缩后的提交作者是维护者本人，PR 作者只作为贡献者记录在本文件里。

这一条不是风格偏好：若改用普通合并（merge commit）或变基合并（rebase），PR 里的原始提交会带着原作者身份进入 `main`，GitHub 的 Contributors 面板就会多出一个人，且只能靠改写历史才能撤回。