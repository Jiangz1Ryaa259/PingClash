$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'build-native.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Native library build failed.' }
& (Join-Path $PSScriptRoot 'collect-notices.ps1')
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-user'
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME -Force | Out-Null
Push-Location $projectRoot
try {
    & .\gradlew.bat --no-daemon --max-workers=2 detekt :app:assembleDebug :app:lintDebug :diagnostics:verifyCore
    if ($LASTEXITCODE -ne 0) { throw 'Android build or verification failed.' }
    $output = Join-Path $projectRoot 'dist'
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $metadata=Get-Content -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\output-metadata.json') -Raw | ConvertFrom-Json
    $version=$metadata.elements[0].versionName
    if($version -notmatch '\A[0-9A-Za-z.+-]{1,60}\z'){throw 'Invalid APK version name'}
    $apk=Join-Path $output ('PingClash-'+$version+'-dev.apk')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $apk -Force
    $digest = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($apk+'.sha256',$digest+'  '+[IO.Path]::GetFileName($apk)+"`n",[Text.Encoding]::ASCII)
    Write-Output "APK: $apk"
    Write-Output "SHA256: $digest"
} finally { Pop-Location }
