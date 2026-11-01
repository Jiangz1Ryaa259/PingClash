param([switch]$AcceptSdkLicense)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$tools=Join-Path $projectRoot '.tools'
$go=Join-Path $tools 'go\bin\go.exe'
New-Item -ItemType Directory -Path (Join-Path $tools 'downloads') -Force|Out-Null
if(-not(Test-Path -LiteralPath $go)){
    $zip=Join-Path $tools 'downloads\go1.27.3.windows-amd64.zip'
    & curl.exe --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --ssl-revoke-best-effort --connect-timeout 10 --max-time 300 --retry 3 --retry-all-errors --retry-max-time 900 --retry-delay 1 --output $zip 'https://go.dev/dl/go1.27.3.windows-amd64.zip'
    if($LASTEXITCODE -ne 0){throw 'Go download failed.'}
    if((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'f26081f438319757f8a1b46d7d0b2c5ec7583d4038e485e0aa725cfde7885fff'){throw 'Official Go archive checksum mismatch.'}
    if(Test-Path -LiteralPath (Join-Path $tools 'go')){throw 'Inspect the incomplete local Go directory before retrying.'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($zip,$tools)
}
$env:Path=(Join-Path $tools 'go\bin')+';'+(Join-Path $tools 'bin')+';'+$env:Path
$env:GOPATH=Join-Path $tools 'gopath'
$env:GOMODCACHE=Join-Path $tools 'go-mod'
$env:GOCACHE=Join-Path $tools 'go-cache'
$env:GOBIN=Join-Path $tools 'bin'
$env:GOTOOLCHAIN='local'
$mobileVersion='v0.0.0-20261011034528-197770444867'
& $go install ("golang.org/x/mobile/cmd/gomobile@"+$mobileVersion)
if($LASTEXITCODE -ne 0){throw 'gomobile installation failed.'}
& $go install ("golang.org/x/mobile/cmd/gobind@"+$mobileVersion)
if($LASTEXITCODE -ne 0){throw 'gobind installation failed.'}
if($AcceptSdkLicense){
    & (Join-Path $PSScriptRoot 'install-android-sdk.ps1') -AcceptSdkLicense -PackageIds @('platforms;android-35','build-tools;35.0.0','platform-tools','ndk;27.2.12479018')
}else{Write-Output 'Android SDK installation skipped. Read its terms and rerun with -AcceptSdkLicense, or configure an existing SDK.'}
$env:ANDROID_HOME=Join-Path $tools 'android-sdk'
$env:ANDROID_NDK_HOME=Join-Path $env:ANDROID_HOME 'ndk\27.2.12479018'
$env:ANDROID_USER_HOME=Join-Path $tools 'android-user'
$env:TEMP=Join-Path $tools 'tmp';$env:TMP=$env:TEMP
New-Item -ItemType Directory -Path $env:TEMP -Force|Out-Null
& gomobile.exe init
if($LASTEXITCODE -ne 0){throw 'gomobile initialization failed.'}
Write-Output 'Local build tools prepared. Set JAVA_HOME to JDK 17+ and run tools/build-android.ps1.'
