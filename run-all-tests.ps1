# ============================================================
# Muse 一键全量测试脚本
# 用法: pwsh .\run-all-tests.ps1
# 作用: 跑 app / memory / ai 三个模块全部单元测试,输出汇总报告
# 退出码: 0 = 全部通过; 1 = 有失败
# ============================================================
param(
    [switch]$Fast,        # 跳过重新编译,直接用缓存(仅测变化)
    [switch]$NoReport     # 不生成汇总报告文件
)

$ErrorActionPreference = "Stop"
$start = Get-Date
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host " Muse 全量测试" -ForegroundColor Cyan
Write-Host " 开始: $(Get-Date -Format 'HH:mm:ss')" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

# 1. 编译 + 跑 app 模块测试
Write-Host "`n[1/3] app 模块测试..." -ForegroundColor Yellow
$appArgs = @(":app:testDebugUnitTest", "--console=plain")
if ($Fast) { $appArgs += "--rerun-tasks" } # Fast 模式也强制跑,确保结果最新
$appOut = & .\gradlew.bat @appArgs 2>&1
$appExit = $LASTEXITCODE
$appOut | Select-Object -Last 5

# 2. memory 模块
Write-Host "`n[2/3] memory 模块测试..." -ForegroundColor Yellow
$memOut = & .\gradlew.bat ":memory:testDebugUnitTest" "--console=plain" 2>&1
$memExit = $LASTEXITCODE
$memOut | Select-Object -Last 5

# 3. ai 模块
Write-Host "`n[3/3] ai 模块测试..." -ForegroundColor Yellow
$aiOut = & .\gradlew.bat ":ai:testDebugUnitTest" "--console=plain" 2>&1
$aiExit = $LASTEXITCODE
$aiOut | Select-Object -Last 5

# ---- 汇总 ----
$elapsed = ((Get-Date) - $start).TotalSeconds
Write-Host "`n==============================================" -ForegroundColor Cyan
Write-Host " 测试完成: $([math]::Round($elapsed,1))s" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

$allPass = ($appExit -eq 0) -and ($memExit -eq 0) -and ($aiExit -eq 0)

# 收集 XML 报告统计
$totalTests = 0; $totalFail = 0; $totalErr = 0; $totalSkip = 0
$failedSuites = @()
$reportDirs = @(
    "$root\app\build\test-results\testDebugUnitTest",
    "$root\memory\build\test-results\testDebugUnitTest",
    "$root\ai\build\test-results\testDebugUnitTest"
)
foreach ($dir in $reportDirs) {
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem $dir -Filter "TEST-*.xml" | ForEach-Object {
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

# 汇总报告文件
if (-not $NoReport) {
    $reportFile = "$root\test-report-$(Get-Date -Format 'yyyyMMdd-HHmm').txt"
    @"
Muse 全量测试报告
时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
耗时: $([math]::Round($elapsed,1))s
结果: $(if ($allPass) { 'PASS' } else { 'FAIL' })
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
