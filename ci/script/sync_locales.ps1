$base = "app/src/main/res/values"
$en = "app/src/main/res/values-en"
$locales = @("values-ja", "values-ko", "values-ru", "values-es", "values-pt-rBR")

# 获取英文目录下所有 strings_*.xml 文件
$enFiles = Get-ChildItem $en -Filter "strings_*.xml"

foreach ($loc in $locales) {
    $dest = "app/src/main/res/$loc"
    if (!(Test-Path $dest)) {
        New-Item -ItemType Directory -Path $dest -Force | Out-Null
    }
    $copied = 0
    foreach ($f in $enFiles) {
        $target = Join-Path $dest $f.Name
        # 只复制目标目录中不存在的文件(保留已有翻译)
        if (!(Test-Path $target)) {
            Copy-Item $f.FullName $target
            $copied++
        }
    }
    Write-Host "$loc : copied $copied files"
}

Write-Host "`nDone. Verifying..."
