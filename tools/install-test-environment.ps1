param([switch]$AcceptSdkLicense)
$ErrorActionPreference='Stop'
if(-not $AcceptSdkLicense){throw 'Read the Android SDK terms and use -AcceptSdkLicense only after accepting them.'}
$projectRoot=Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'install-android-sdk.ps1') -AcceptSdkLicense -PackageIds 'system-images;android-35;default;x86_64' -RepositoryUrl 'https://dl.google.com/android/repository/sys-img/android/sys-img2-1.xml'
$target=Join-Path $projectRoot '.tools\emulator-37.2.12'
if(-not(Test-Path -LiteralPath (Join-Path $target 'emulator\emulator.exe'))){
    if(Test-Path -LiteralPath $target){throw 'Inspect the incomplete compatibility emulator directory before retrying.'}
    $archive=Join-Path $projectRoot '.tools\downloads\emulator-windows_x64-16428233.zip'
    if(-not(Test-Path -LiteralPath $archive)){
        & curl.exe --fail --silent --show-error --location --ssl-revoke-best-effort --connect-timeout 10 --max-time 600 --retry 2 --output $archive 'https://edgedl.me.gvt1.com/edgedl/android/repository/emulator-windows_x64-16428233.zip'
        if($LASTEXITCODE -ne 0){throw 'Test emulator download failed.'}
    }
    if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne '3b524b6e0f1f5506d3d81af62ec9ce15b2a6f80f467e99574e167514ca181401'){throw 'Official emulator SHA256 mismatch.'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($archive,$target)
}
Write-Output 'Project Android 35 test image and official emulator 37.2.12 are ready.'
