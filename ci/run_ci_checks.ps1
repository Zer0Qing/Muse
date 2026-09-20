[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('ci-scripts', 'lanes', 'static', 'unit', 'debug')]
    [string] $Lane,
    # P4-9: 消费 pr_check 的 lanes 输出 — CI 传入受影响作用域(逗号分隔)。
    # 为空 = 未知/全量(tag、push、本地),所有脚本都跑,保持向后兼容。
    [string] $ChangedScopes = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

$python = if (Get-Command python3 -ErrorAction SilentlyContinue) { 'python3' } else { 'python' }
$gradle = if ($IsWindows -and (Test-Path './gradlew.bat')) { './gradlew.bat' } else { './gradlew' }
$skipReleaseGuards = @('-PreleaseSkipVersionCheck=true', '-PreleaseSkipKeystoreCheck=true')

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Command,
        [Parameter(Mandatory = $false)]
        [string[]] $Arguments = @()
    )

    Write-Host "> $Command $($Arguments -join ' ')"
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "CI command failed with exit code ${LASTEXITCODE}: $Command"
    }
}

switch ($Lane) {
    'ci-scripts' {
        # 逐文件独立执行:python 一次只把第一个文件当 __main__ 运行,
        # 多文件同进程会让后面的测试文件静默跳过(假绿)。
        @(
            'ci/test/test_pr_check.py',
            'ci/test/test_engineering_discipline.py',
            'ci/test/test_hardcoded_cjk.py',
            'ci/test/test_release_preflight.py',
            'ci/test/test_check_ktlint_report.py',
            'ci/test/test_check_detekt_debt.py',
            # CMP-11 / I18N-02 / A11Y-03 护栏脚本的单测
            'ci/test/test_component_convergence.py',
            'ci/test/test_translation_residue.py',
            'ci/test/test_font_scale_clipping.py'
        ) | ForEach-Object {
            Invoke-Checked $python @($_)
        }
    }
    'lanes' {
        # tag 发布没有 PR base；与 origin/main 比较会把已发布提交误报为整批变更。
        # tag 仍执行下面全部仓库质量脚本，只跳过只对 PR 变更有意义的路由分析。
        if ($env:GITHUB_REF_TYPE -ne 'tag') {
            Invoke-Checked $python @('ci/script/pr_check.py', '--base', 'origin/main', '--output', 'json')
        } else {
            Write-Host 'tag build: skip PR diff routing, keep repository lane checks'
        }
        # P4-9: 按 pr_check 作用域路由 — 只跑受影响类别;未传作用域(=全量)全部跑。
        $scopes = @()
        if (-not [string]::IsNullOrWhiteSpace($ChangedScopes)) {
            $scopes = $ChangedScopes.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
        }
        function Test-ScopeAny([string[]]$Need) {
            if ($scopes.Count -eq 0) { return $true }  # 未知 → 全跑
            foreach ($n in $Need) { if ($scopes -contains $n) { return $true } }
            return $false
        }
        function Invoke-LaneScript([string]$Name, [string[]]$Need) {
            if (Test-ScopeAny $Need) {
                Invoke-Checked $python @("ci/script/$Name")
            } else {
                Write-Host "skip $Name (no affected scope in $($Need -join '/'))"
            }
        }
        # 快速卫生检查:无论变更什么都要跑
        @(
            'check_repo_hygiene.py',
            'check_engineering_discipline.py',
            'check_hardcoded_cjk.py'
        ) | ForEach-Object {
            Invoke-Checked $python @("ci/script/$_")
        }
        Invoke-LaneScript 'check_localizations.py' @('localization')
        Invoke-LaneScript 'check_design_tokens.py' @('android_jvm', 'android_resources', 'android_full')
        Invoke-LaneScript 'check_hardcoded_font_size.py' @('android_jvm', 'android_resources', 'android_full')
        Invoke-LaneScript 'check_icon_content_description.py' @('android_jvm', 'android_resources', 'android_full')
        Invoke-LaneScript 'check_touch_target.py' @('android_jvm', 'android_resources', 'android_full')
    }
    'static' {
        Invoke-Checked $gradle (@(
            'detekt',
            'ktlintCheck',
            # P4-1: 真 lint Kotlin 源(AGP9 下 ktlint-gradle 只查 .kts)。
            # 各模块 ktlintKotlinSourceCheck 用官方 CLI 扫 .kt,首跑自动生成 ktlint-baseline.xml(已入库),
            # 之后新增问题即失败;check_ktlint_report.py 再断言各模块报告非空且记录了真实 lint 文件数。
            'ktlintKotlinSourceCheck',
            'lintDebug',
            'koverXmlReport',
            # P4-5: 覆盖率门禁补全 — common/accessibility/material3 补上 minBound 并纳入校验
            ':ai:koverCachedVerifyDebug',
            ':memory:koverCachedVerifyDebug',
            ':app:koverCachedVerifyDebug',
            ':common:koverCachedVerifyDebug',
            ':accessibility:koverCachedVerifyDebug',
            ':material3:koverCachedVerifyDebug'
        ) + $skipReleaseGuards)
        # P4-2: detekt 债务上限断言(总量 + 单模块双围栏,禁止新增;按批次清理后手动调低 cap)
        Invoke-Checked $python @('ci/script/check_detekt_debt.py')
        Invoke-Checked $python @('ci/script/check_ktlint_report.py')
        # CMP-11: 组件收敛护栏 — ui/** 直接引用 M3 交互控件只降不升(基线外新文件即失败)
        Invoke-Checked $python @('ci/script/check_component_convergence.py')
        # I18N-02: 非中文语言包 CJK 残留只降不升(基线全 0,高于基线即失败)
        Invoke-Checked $python @('ci/script/check_translation_residue.py')
        # A11Y-03: 大字体裁切护栏 — 承载文本的固定高度(>=32dp)只降不升(基线全 0)
        Invoke-Checked $python @('ci/script/check_font_scale_clipping.py')
    }
    'unit' {
        Invoke-Checked $gradle (@(
            ':app:testDebugUnitTest',
            ':memory:testDebugUnitTest',
            ':ai:testDebugUnitTest',
            ':common:testDebugUnitTest',
            ':accessibility:testDebugUnitTest',
            # P4-4: material3 此前漏在单测 lane 之外
            ':material3:testDebugUnitTest'
        ) + $skipReleaseGuards)
    }
    'debug' {
        Invoke-Checked $gradle (@('assembleDebug') + $skipReleaseGuards)
    }
}

Write-Host "CI lane '$Lane' PASS"
