# OKK 发版打包：固定 assembleRelease（R8 压缩混淆 + 资源裁剪）
# 产物命名规范：OKK-{版本号}.apk（如 OKK-1.2.2.apk），同时生成 OKK-latest.apk
# 用法:
#   powershell -File pack.ps1
#   powershell -File pack.ps1 -Install
#   powershell -File pack.ps1 -NoInstall
param(
    [switch]$Install,
    [switch]$NoInstall
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$OutDir = Join-Path (Split-Path $Root -Parent) "APK"
if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
}

Push-Location $Root
try {
    Write-Host "==> assembleRelease (minify + shrink + obfuscate)"
    & .\gradlew.bat :app:assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease
    if ($LASTEXITCODE -ne 0) {
        throw "assembleRelease failed: $LASTEXITCODE"
    }

    $apk = Join-Path $Root "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $apk)) {
        $apk = Get-ChildItem (Join-Path $Root "app\build\outputs\apk\release") -Filter "*.apk" |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $apk -or -not (Test-Path $apk)) {
        throw "release apk not found"
    }

    $verName = "release"
    $aapt = Get-ChildItem "D:\Tool\Android\Sdk\build-tools" -Recurse -Filter "aapt.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
    if ($aapt) {
        try {
            # aapt 无法打开含中文的路径（OKK\源码 里的“源码”会转成乱码），先复制到 ASCII 临时路径再 badging
            $tmpApk = Join-Path $env:TEMP "_okk_badging.apk"
            Copy-Item $apk $tmpApk -Force
            $line = (& $aapt dump badging $tmpApk 2>$null | Select-String "package:.*versionName='([^']+)'" | Select-Object -First 1).Line
            if ($line -match "versionName='([^']+)'") {
                $verName = $Matches[1]
            }
            Remove-Item $tmpApk -Force -ErrorAction SilentlyContinue
        } catch {}
    }

    $latest = Join-Path $OutDir "OKK-latest.apk"
    $named = Join-Path $OutDir "OKK-$verName.apk"
    Copy-Item $apk $latest -Force
    Copy-Item $apk $named -Force

    $len = (Get-Item $named).Length
    Write-Host "OK  $named"
    Write-Host "    size = $([math]::Round($len/1MB, 2)) MB"
    Write-Host "    also -> OKK-latest.apk"

    if (-not $NoInstall) {
        $adb = "D:\Data\platform-tools\adb.exe"
        if (-not (Test-Path $adb)) { $adb = "adb" }
        Write-Host "==> installing to device via $adb ..."
        & $adb install -r $named
        & $adb shell am force-stop com.tencent.mm
        Write-Host "installed + wechat force-stop done."
    }
}
catch {
    Write-Error $_
    exit 1
}
finally {
    Pop-Location
}
