$orig = Get-Content -Path 'd:\1test\1muse\app\src\main\java\io\zer0\muse\ui\ChatViewModel.kt' -Encoding UTF8
$new = Get-Content -Path 'd:\1test\1muse\app\src\main\java\io\zer0\muse\ui\LaunchStreamRefactor.kt.txt' -Encoding UTF8
Write-Host "Original total lines: $($orig.Count)"
Write-Host "Lines before (1-3165): 3165"
Write-Host "Lines to replace (3166-4181): 1016"
Write-Host "Lines after (4182-end): $($orig.Count - 4181)"
Write-Host "New code lines: $($new.Count)"

# Build result: lines 1-3165 (index 0-3164) + new code + lines 4182-end (index 4181-end)
$before = $orig[0..3164]
$after = $orig[4181..($orig.Count - 1)]
$result = @()
$result += $before
$result += $new
$result += $after
Write-Host "Result total lines: $($result.Count)"

# Write back with UTF-8 BOM (matching original encoding)
$utf8bom = New-Object System.Text.UTF8Encoding $true
[System.IO.File]::WriteAllLines('d:\1test\1muse\app\src\main\java\io\zer0\muse\ui\ChatViewModel.kt', $result, $utf8bom)
Write-Host "Done"
