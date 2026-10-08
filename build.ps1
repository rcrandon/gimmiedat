<#
  Rebuilds the Gimmie 'Dat APK.

    .\build.ps1                     # signed release APK for phones (arm64)  → dist\gimmiedat-<version>.apk
    .\build.ps1 -RefreshYtDlp       # also pull the newest yt-dlp into the APK first
    .\build.ps1 -Emulator           # arm64 + x86_64, so it also runs on a desktop emulator
    .\build.ps1 -Install            # build, then adb install onto the connected phone

  Signing uses keystore\gimmiedat-release.jks (created on first build if missing). Keep that
  file: Android only installs an update over an existing Gimmie 'Dat if it's signed by the same key.
#>
param(
    [switch]$RefreshYtDlp,
    [switch]$Emulator,
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# JDK 17 on Windows opens a unix socket under %TEMP%; long temp paths break Gradle's daemon.
New-Item -ItemType Directory -Force C:\gtmp | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\gtmp'

if (-not (Test-Path local.properties)) {
    $sdk = "$env:LOCALAPPDATA\Android\Sdk"
    if (-not (Test-Path $sdk)) { throw "Android SDK not found at $sdk, install Android Studio or set sdk.dir in local.properties" }
    "sdk.dir=$($sdk -replace '\\','\\' -replace ':','\:')" | Set-Content -Encoding ascii local.properties
}

if (-not (Test-Path keystore\keystore.properties)) {
    Write-Host 'creating a release signing key in keystore\...'
    New-Item -ItemType Directory -Force keystore | Out-Null
    $pw = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 28 | ForEach-Object { [char]$_ })
    & keytool -genkeypair -keystore keystore\gimmiedat-release.jks -alias gimmiedat -keyalg RSA -keysize 4096 `
        -validity 36500 -storepass $pw -keypass $pw -dname 'CN=Gimmie Dat for android, O=Gimmie Dat' | Out-Null
    "storeFile=keystore/gimmiedat-release.jks`nstorePassword=$pw`nkeyAlias=gimmiedat`nkeyPassword=$pw" |
        Set-Content -Encoding ascii keystore\keystore.properties
}

if ($RefreshYtDlp) {
    $tag = (Invoke-RestMethod https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest).tag_name
    Write-Host "bundling yt-dlp $tag"
    Invoke-WebRequest "https://github.com/yt-dlp/yt-dlp/releases/download/$tag/yt-dlp" -OutFile app\src\main\res\raw\ytdlp
    (Get-Content app\build.gradle.kts -Raw) -replace 'val bundledYtDlp = "[^"]+"', "val bundledYtDlp = `"$tag`"" |
        Set-Content -NoNewline app\build.gradle.kts
}

$abis = if ($Emulator) { 'arm64-v8a,x86_64' } else { 'arm64-v8a' }
& .\gradlew.bat --console=plain testReleaseUnitTest assembleRelease "-Pgimmiedat.abis=$abis"
if ($LASTEXITCODE -ne 0) { throw 'build failed' }

$version = (Select-String -Path app\build.gradle.kts -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force dist | Out-Null
$suffix = if ($Emulator) { '-with-x86_64' } else { '' }
$out = "dist\gimmiedat-$version$suffix.apk"
Copy-Item app\build\outputs\apk\release\app-release.apk $out -Force
$mb = [math]::Round((Get-Item $out).Length / 1MB, 1)
Write-Host "done: $out ($mb MB)"

if ($Install) {
    & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r $out
}
