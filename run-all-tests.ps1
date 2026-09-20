# ============================================================
# Muse 一键全量测试脚本
# 用法: pwsh .\run-all-tests.ps1 [-Fast] [-NoReport] [-SkipAndroidTest]
# 作用: 跑全部 6 个模块(app/memory/ai/common/accessibility/material3)单元测试,
#       并在检测到已连接设备/模拟器时执行 app androidTest(P4-4 补全)。
# 退出码: 0 = 全部通过; 1 = 有失败
# ============================================================
param(
    [switch]$Fast,           # 跳过重新编译,直接用缓存(仅测变化)
    [switch]$NoReport,       # 不生成汇总报告文件
    [switch]$SkipAndroidTest # 强制执行 app androidTest(即使未检测到设备)
)

$ErrorActionPreference = "Stop"
$start = Get-Date
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host " Muse 全量测试" -ForegroundColor Cyan
Write-Host " 开始: $(Get-Date -Format 'HH:mm:ss')" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

# 1. 全部单元测试模块(P4-4: 补上 common / accessibility / material3)
$moduleTasks = @(
    ":app:testDebugUnitTest",
    ":memory:testDebugUnitTest",
    ":ai:testDebugUnitTest",
    ":common:testDebugUnitTest",
    ":accessibility:testDebugUnitTest",
    ":material3:testDebugUnitTest"
)
$unitArgs = $moduleTasks + @("--console=plain")
if ($Fast) { $unitArgs += "--rerun-tasks" }
Write-Host "`n[1/2] 单元测试(6 模块)..." -ForegroundColor Yellow
$unitOut = & .\gradlew.bat @unitArgs 2>&1
$unitExit = $LASTEXITCODE
$unitOut | Select-Object -Last 5

# 2. app androidTest(需要设备/模拟器;没有则跳过并提示)
$androidExit = 0
$androidRan = $false
$androidOut = @()
$hasDevice = $false
if (-not $SkipAndroidTest) {
    $adb = "adb"
    if (Get-Command adb -ErrorAction SilentlyContinue) {
        $devices = (& adb devices 2>$null | Select-String -Pattern "^\S+\s+device$")
        $hasDevice = $devices -and $devices.Count -gt 0
    }
}
if ($SkipAndroidTest) {
    Write-Host "`n[2/2] androidTest 已按 -SkipAndroidTest 跳过" -ForegroundColor DarkYellow
} elseif (-not $hasDevice) {
    Write-Host "`n[2/2] androidTest 跳过:未检测到已连接的设备/模拟器(adb devices 为空)。" -ForegroundColor DarkYellow
    Write-Host "       连上设备后重跑本脚本,或用 -SkipAndroidTest 有意跳过。" -ForegroundColor DarkYellow
} else {
    Write-Host "`n[2/2] app androidTest(设备已连接)..." -ForegroundColor Yellow
    $androidOut = & .\gradlew.bat ":app:connectedDebugAndroidTest" "--console=plain" 2>&1
    $androidExit = $LASTEXITCODE
    $androidRan = $true
    $androidOut | Select-Object -Last 5
}

# ---- 汇总 ----
$elapsed = ((Get-Date) - $start).TotalSeconds
Write-Host "`n==============================================" -ForegroundColor Cyan
Write-Host " 测试完成: $([math]::Round($elapsed,1))s" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

$allPass = ($unitExit -eq 0) -and ($androidExit -eq 0)

# 收集 XML 报告统计(单测 6 模块 + androidTest 报告若有)
$totalTests = 0; $totalFail = 0; $totalErr = 0; $totalSkip = 0
$failedSuites = @()
$reportDirs = @(
    "$root\app\build\test-results\testDebugUnitTest",
    "$root\memory\build\test-results\testDebugUnitTest",
    "$root\ai\build\test-results\testDebugUnitTest",
    "$root\common\build\test-results\testDebugUnitTest",
    "$root\accessibility\build\test-results\testDebugUnitTest",
    "$root\material3\build\test-results\testDebugUnitTest",
    "$root\app\build\outputs\androidTest-results\connected"
)
foreach ($dir in $reportDirs) {
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem $dir -Filter "TEST-*.xml" -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            [xml]$x = Get-Content $_.FullName
            $totalTests += [int]$x.testsuite.tests
            $totalFail  += [int]$x.testsuite.failures
            $totalErr   += [int]$x.testsuite.errors
            $totalSkip  += [int]$x.testsuite.skipped
            if (([int]$x.testsuite.failures + [int]$x.testsuite.errors) -gt 0) {
                $failedSuites += $x.testsuite.name
            }
        } catch { }
    }
}

Write-Host ""
Write-Host "  用例总数: $totalTests" -ForegroundColor White
Write-Host "  通过:     $($totalTests - $totalFail - $totalErr - $totalSkip)" -ForegroundColor Green
Write-Host "  失败:     $totalFail" -ForegroundColor $(if ($totalFail -gt 0) { "Red" } else { "Green" })
Write-Host "  错误:     $totalErr" -ForegroundColor $(if ($totalErr -gt 0) { "Red" } else { "Green" })
Write-Host "  跳过:     $totalSkip" -ForegroundColor Yellow

if ($failedSuites.Count -gt 0) {
    Write-Host "`n  失败套件:" -ForegroundColor Red
    $failedSuites | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
}

if ($androidRan) {
    Write-Host "`n  androidTest: 已执行" -ForegroundColor Green
} else {
    Write-Host "`n  androidTest: 跳过(无设备或 -SkipAndroidTest)" -ForegroundColor DarkYellow
}

# 汇总报告文件
if (-not $NoReport) {
    $reportFile = "$root\test-report-$(Get-Date -Format 'yyyyMMdd-HHmm').txt"
    @"
Muse 全量测试报告
时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
耗时: $([math]::Round($elapsed,1))s
结果: $(if ($allPass) { 'PASS' } else { 'FAIL' })
androidTest: $(if ($androidRan) { 'EXECUTED' } else { 'SKIPPED (no device or -SkipAndroidTest)' })
用例: $totalTests (通过 $($totalTests - $totalFail - $totalErr - $totalSkip) / 失败 $totalFail / 错误 $totalErr / 跳过 $totalSkip)
$(if ($failedSuites.Count -gt 0) { "失败套件:`n" + ($failedSuites | ForEach-Object { "  - $_" }) -join "`n" } else { '' })
"@ | Set-Content $reportFile
    Write-Host "`n  报告已保存: $reportFile" -ForegroundColor Cyan
}

if ($allPass) {
    Write-Host "`n  结果: 全部通过 ✓  可以发布" -ForegroundColor Green
} else {
    Write-Host "`n  结果: 存在失败 ✗  请先修复再发布" -ForegroundColor Red
}
exit $(if ($allPass) { 0 } else { 1 })