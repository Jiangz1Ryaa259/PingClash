$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'build-terminal.ps1')
$env:Path = (Join-Path $projectRoot '.tools\go\bin') + ';' + (Join-Path $projectRoot '.tools\bin') + ';' + $env:Path
$env:GOPATH = Join-Path $projectRoot '.tools\gopath'
$env:GOMODCACHE = Join-Path $projectRoot '.tools\go-mod'
$env:GOCACHE = Join-Path $projectRoot '.tools\go-cache'
$env:GOTOOLCHAIN = 'local'
$env:GOFLAGS = '-p=2'
$env:ANDROID_HOME = Join-Path $projectRoot '.tools\android-sdk'
$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk\27.2.12479018'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-user'
$env:TEMP = Join-Path $projectRoot '.tools\tmp'
$env:TMP = $env:TEMP
$output = Join-Path $projectRoot '.tools\native'
New-Item -ItemType Directory -Path $output,$env:TEMP -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $env:ANDROID_NDK_HOME 'source.properties'))) { throw 'Install NDK 27.2.12479018 first.' }
Push-Location (Join-Path $projectRoot 'core')
try {
    $nativeArgs = @('bind', '-tags=with_gvisor', '-trimpath', '-target', 'android/arm64,android/amd64', '-androidapi', '29',
        '-javapkg', 'dev.pingclash', '-ldflags', '-s -w -extldflags=-Wl,-z,max-page-size=16384',
        '-o', (Join-Path $output 'pingcore.aar'), './mobile')
    & gomobile.exe @nativeArgs
    if ($LASTEXITCODE -ne 0) { throw 'Android native library build failed.' }
    $hash = (Get-FileHash -LiteralPath (Join-Path $output 'pingcore.aar') -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "AAR SHA256: $hash"
} finally { Pop-Location }
