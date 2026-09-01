[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('ci-scripts', 'lanes', 'static', 'unit', 'debug')]
    [string] $Lane
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
            'ci/test/test_release_preflight.py'
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
        @(
            'check_repo_hygiene.py',
            'check_engineering_discipline.py',
            'check_hardcoded_cjk.py',
            'check_localizations.py',
            'check_design_tokens.py',
            'check_hardcoded_font_size.py',
            'check_icon_content_description.py',
            'check_touch_target.py'
        ) | ForEach-Object {
            Invoke-Checked $python @("ci/script/$_")
        }
    }
    'static' {
        Invoke-Checked $gradle (@(
            'detekt',
            'ktlintCheck',
            'lintDebug',
            'koverXmlReport',
            ':ai:koverCachedVerifyDebug',
            ':memory:koverCachedVerifyDebug',
            ':app:koverCachedVerifyDebug'
        ) + $skipReleaseGuards)
    }
    'unit' {
        Invoke-Checked $gradle (@(
            ':app:testDebugUnitTest',
            ':memory:testDebugUnitTest',
            ':ai:testDebugUnitTest',
            ':common:testDebugUnitTest',
            ':accessibility:testDebugUnitTest'
        ) + $skipReleaseGuards)
    }
    'debug' {
        Invoke-Checked $gradle (@('assembleDebug') + $skipReleaseGuards)
    }
}

Write-Host "CI lane '$Lane' PASS"
