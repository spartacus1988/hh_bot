# -*- coding: utf-8 -*-
# Сборка hh_bot.apk без Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$ProjectDir = $PSScriptRoot

function Find-Jdk {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
        return $env:JAVA_HOME
    }
    $candidates = @(
        "E:\android\toolchain\jdk",
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    )
    foreach ($base in $candidates) {
        if (-not (Test-Path $base)) { continue }
        if (Test-Path (Join-Path $base "bin\javac.exe")) { return $base }
        $dir = Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName "bin\javac.exe") } |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($dir) { return $dir.FullName }
    }
    throw "JDK 17 не найден. Задайте JAVA_HOME."
}

function Find-Sdk {
    $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "E:\android\toolchain\sdk",
        "$env:LOCALAPPDATA\Android\Sdk") | Where-Object { $_ }
    foreach ($root in $roots) {
        if (Test-Path (Join-Path $root "build-tools")) { return $root }
    }
    throw "Android SDK не найден. Задайте ANDROID_HOME."
}

$Jdk = Find-Jdk
$Sdk = Find-Sdk
$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk
$env:Path = "$Jdk\bin;$Sdk\platform-tools;$env:Path"

$BuildTools = Get-ChildItem (Join-Path $Sdk "build-tools") -Directory |
    Sort-Object { [int]($_.Name.Split('.')[0]) } -Descending | Select-Object -First 1
$BuildTools = $BuildTools.FullName
$AndroidJar = Join-Path $Sdk "platforms\android-34\android.jar"
if (-not (Test-Path $AndroidJar)) {
    throw "Не найден android.jar: $AndroidJar (нужен platforms;android-34)"
}

$aapt2 = Join-Path $BuildTools "aapt2.exe"
$d8 = Join-Path $BuildTools "d8.bat"
$zipalign = Join-Path $BuildTools "zipalign.exe"
$apksigner = Join-Path $BuildTools "apksigner.bat"
$javac = Join-Path $Jdk "bin\javac.exe"
$jar = Join-Path $Jdk "bin\jar.exe"
$keytool = Join-Path $Jdk "bin\keytool.exe"

$Source = Join-Path $ProjectDir "app\src\main"
$Out = Join-Path $ProjectDir "build\manual"
$Gen = Join-Path $Out "gen"
$Classes = Join-Path $Out "classes"
$Dex = Join-Path $Out "dex"

if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Force $Gen, $Classes, $Dex | Out-Null

Write-Host "==> aapt2 compile"
& $aapt2 compile --dir (Join-Path $Source "res") -o (Join-Path $Out "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# Манифест для Gradle не содержит package (там используется namespace),
# а aapt2 для генерации R.java требует package — подставляем его в копию.
$ManifestText = [System.IO.File]::ReadAllText(
    (Join-Path $Source "AndroidManifest.xml"), [System.Text.Encoding]::UTF8)
$ManifestText = $ManifestText -replace '<manifest\s', '<manifest package="ru.hhbot" '
$Manifest = Join-Path $Out "AndroidManifest.xml"
[System.IO.File]::WriteAllText($Manifest, $ManifestText, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "==> aapt2 link"
& $aapt2 link `
    -o (Join-Path $Out "base.apk") `
    -I $AndroidJar `
    --manifest $Manifest `
    -R (Join-Path $Out "res.zip") `
    --java $Gen `
    --min-sdk-version 29 `
    --target-sdk-version 34 `
    --version-code 1 `
    --version-name "0.1" `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "==> javac"
$javaFiles = @()
$javaFiles += Get-ChildItem (Join-Path $Source "java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$javaFiles += Get-ChildItem $Gen -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options `
    -classpath $AndroidJar `
    -d $Classes $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "==> d8"
& $jar --create --file (Join-Path $Out "classes.jar") -C $Classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& $d8 --lib $AndroidJar --min-api 29 --release `
    --output $Dex (Join-Path $Out "classes.jar")
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "==> package classes.dex"
Copy-Item (Join-Path $Out "base.apk") (Join-Path $Out "unsigned.apk") -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open(
    (Join-Path $Out "unsigned.apk"), [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $entry = $zip.GetEntry("classes.dex")
    if ($entry) { $entry.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, (Join-Path $Dex "classes.dex"), "classes.dex",
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}

Write-Host "==> zipalign"
& $zipalign -f -p 4 (Join-Path $Out "unsigned.apk") (Join-Path $Out "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$KeystoreDir = Join-Path $ProjectDir "keystore"
New-Item -ItemType Directory -Force $KeystoreDir | Out-Null
$Keystore = Join-Path $KeystoreDir "hhbot.keystore"
if (-not (Test-Path $Keystore)) {
    Write-Host "==> keytool (ключ подписи)"
    & $keytool -genkeypair -noprompt `
        -keystore $Keystore -storepass android -alias hhbot `
        -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=HH Bot,O=HHBot,C=RU"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Host "==> apksigner"
$Apk = Join-Path $ProjectDir "hh_bot.apk"
& $apksigner sign --ks $Keystore --ks-key-alias hhbot `
    --ks-pass pass:android --key-pass pass:android `
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false `
    --out $Apk (Join-Path $Out "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

& $apksigner verify --print-certs $Apk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

Write-Host ""
Write-Host "Готово: $Apk"
$apkSize = (Get-Item $Apk).Length
if ($apkSize -ge 1MB) {
    Write-Host ("Размер: {0:N1} МБ" -f ($apkSize / 1MB))
} else {
    Write-Host ("Размер: {0:N1} КБ" -f ($apkSize / 1KB))
}
