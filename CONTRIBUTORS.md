# Contributors

Muse 的代码与文案来自这些人的贡献。

## 本地化

- **joselofarias-byte**（José Luis Farías）— 西班牙语本地化：Android 全量文案、Web 控制台文案，以及配套的字符串抽取与 4 个回归测试。合并于 PR #14。
- **PedroFlores199**（Pedro Flores）— 西班牙语校对：补齐 SenseVoice 本地语音识别 26 条文案（`asr_*`），修正 1107 条既有翻译——截断的补全、品牌与技术名误译（SOCKS5 / Whisper / Agnes 曾被当作普通词翻译）、按钮词纠正、敬语 usted 统一为 tú、重音符与 ñ 补回、全角标点改为 « »。合并于 PR #15。

## 缺陷修复

- **pnslfan** — 窄窗口（自由窗口 / 分屏）下的两处布局缺陷：`rememberWindowWidthClass()` 依赖 `Configuration.screenWidthDp`，部分 OEM 在窗口 resize 时不更新该值，窄窗被误判成 Expanded（右栏被挤出可视区、点会话无反应、快捷胶囊消失）；以及双栏左栏 `fillMaxSize().width(400.dp)` 中 `width` 不生效导致右栏宽度为 0。在 Lenovo TB375FC / Android 16 上实测定位。合并于 PR #16。

---

贡献者姓名按贡献类型归档；提交历史为保持作者归属简洁，统一记在项目维护者名下，实际贡献记录见上表与对应 PR。

## 合并方式

外部 PR 一律**在本地压缩合并（squash）后再推**，不用 GitHub 网页的合并按钮：压缩后的提交作者是维护者本人，PR 作者只作为贡献者记录在本文件里。

这条不是风格偏好：GitHub 的 squash 只把 **committer** 改成合并者，**author 字段会保留 PR 原作者**；普通合并与变基合并更差，原作者的提交会整条进入 `main`，GitHub 的 Contributors 面板就会多出一个人。