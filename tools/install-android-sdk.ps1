param(
    [switch]$AcceptSdkLicense,
    [string[]]$PackageIds = @('platforms;android-35','build-tools;35.0.0','platform-tools'),
    [string]$RepositoryUrl = 'https://dl.google.com/android/repository/repository2-1.xml'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (-not $AcceptSdkLicense) {
    throw 'Read https://developer.android.com/studio/terms and explicitly accept the SDK license before using -AcceptSdkLicense.'
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $projectRoot '.tools'
$sdkRoot = Join-Path $toolsRoot 'android-sdk'
$downloadRoot = Join-Path $toolsRoot 'downloads'
$extractRoot = Join-Path $toolsRoot 'android-extract'
New-Item -ItemType Directory -Path $sdkRoot,$downloadRoot,$extractRoot -Force | Out-Null
$repositoryUri = [uri]$RepositoryUrl
if ($repositoryUri.Scheme -ne 'https' -or $repositoryUri.Host -ne 'dl.google.com' -or -not $repositoryUri.AbsolutePath.StartsWith('/android/repository/')) { throw 'Only the official Google SDK repository is supported.' }
$repositoryBase = $RepositoryUrl.Substring(0, $RepositoryUrl.LastIndexOf('/') + 1)
$metadata = Join-Path $toolsRoot ('android-' + [IO.Path]::GetFileName($repositoryUri.AbsolutePath))
if (-not (Test-Path -LiteralPath $metadata)) {
    & curl.exe --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --connect-timeout 10 --max-time 60 --retry 3 --retry-all-errors --retry-max-time 180 --retry-delay 1 --output $metadata $RepositoryUrl
    if ($LASTEXITCODE -ne 0) { throw 'Android repository metadata download failed.' }
}
[xml]$repository = Get-Content -LiteralPath $metadata -Raw
$packages = $repository.SelectNodes('//*[local-name()="remotePackage"]')
$license = $repository.SelectNodes('//*[local-name()="license"]') | Where-Object { $_.id -eq 'android-sdk-license' } | Select-Object -First 1
if (-not $license) { throw 'Expected Android SDK license is missing from metadata.' }

function Assert-WorkspacePath([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    $allowed = [IO.Path]::GetFullPath($toolsRoot).TrimEnd('\') + '\'
    if (-not $absolute.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'SDK installation path escaped the project tools directory.'
    }
    return $absolute
}

foreach ($id in $PackageIds) {
    # Extension SDK archives share the base package path in this repository XML.
    $package = $packages | Where-Object {
        $_.path -eq $id -and $_.'uses-license'.ref -eq 'android-sdk-license' -and -not $_.obsolete -and -not $_.'type-details'.'extension-level' -and
        ($id -ne 'platforms;android-35' -or $_.archives.archive.complete.url -match '^platform-35_r\d+\.zip$')
    } | Select-Object -First 1
    if (-not $package -or $package.'uses-license'.ref -ne 'android-sdk-license') {
        throw "Package missing or requires a different license: $id"
    }
    $archive = $package.archives.archive | Where-Object { $_.'host-os' -eq 'windows' -or -not $_.'host-os' } | Select-Object -First 1
    $url = [string]$archive.complete.url
    if ($url -notmatch '^[A-Za-z0-9_.-]+\.zip$') { throw 'Unexpected archive URL.' }
    $zipPath = Join-Path $downloadRoot $url
    $expected = $archive.complete.checksum.InnerText
    if (-not $expected) { $expected = [string]$archive.complete.checksum }
    $target = Assert-WorkspacePath (Join-Path $sdkRoot ($id.Replace(';','\')))
    if ($id -eq 'platforms;android-35' -and (Test-Path -LiteralPath (Join-Path $target 'source.properties'))) {
        $identity = Get-Content -LiteralPath (Join-Path $target 'source.properties') -Raw
        if ($identity -match 'AndroidVersion.IsBaseSdk=false') {
            $extension = [regex]::Match($identity, 'AndroidVersion.ExtensionLevel=(\d+)').Groups[1].Value
            if (-not $extension) { throw 'Cannot identify installed extension SDK.' }
            $preserved = Assert-WorkspacePath ($target + '-ext' + $extension)
            if (Test-Path -LiteralPath $preserved) { throw 'Extension SDK destination already exists.' }
            Move-Item -LiteralPath $target -Destination $preserved
        }
    }
    if (Test-Path -LiteralPath (Join-Path $target 'source.properties')) {
        Write-Output "Already installed: $id"
        continue
    }
    $downloadNeeded = -not (Test-Path -LiteralPath $zipPath)
    if (-not $downloadNeeded) { $downloadNeeded = (Get-Item -LiteralPath $zipPath).Length -ne [long]$archive.complete.size }
    if ($downloadNeeded) {
        Write-Output "Downloading: $id"
        & curl.exe --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --connect-timeout 10 --max-time 600 --speed-time 30 --speed-limit 4096 --retry 3 --retry-all-errors --retry-max-time 900 --retry-delay 1 --continue-at - --output $zipPath ($repositoryBase + $url)
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $id" }
    }
    if ((Get-FileHash -LiteralPath $zipPath -Algorithm SHA1).Hash.ToLowerInvariant() -ne $expected.ToLowerInvariant()) {
        throw "Official archive checksum mismatch: $id"
    }
    $unpack = Assert-WorkspacePath (Join-Path $extractRoot ([guid]::NewGuid().ToString('N')))
    [IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $unpack)
    $directories = @(Get-ChildItem -LiteralPath $unpack -Directory)
    if ($directories.Count -ne 1) { throw "Unexpected archive structure: $id" }
    $source = Assert-WorkspacePath $directories[0].FullName
    if (-not (Test-Path -LiteralPath (Join-Path $source 'source.properties'))) {
        throw "Missing package identity: $id"
    }
    if (Test-Path -LiteralPath $target) { throw "Target already exists, inspect before replacing: $target" }
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Move-Item -LiteralPath $source -Destination $target
    Write-Output "Installed: $id"
}

# This writes only the accepted license fingerprint, not a blanket preview/NDK acceptance.
$licenseDirectory = Join-Path $sdkRoot 'licenses'
New-Item -ItemType Directory -Path $licenseDirectory -Force | Out-Null
$licenseBytes = [Text.Encoding]::UTF8.GetBytes($license.InnerText.Trim())
$sha1 = [Security.Cryptography.SHA1]::Create()
try { $fingerprint = ([BitConverter]::ToString($sha1.ComputeHash($licenseBytes))).Replace('-','').ToLowerInvariant() }
finally { $sha1.Dispose() }
$licensePath = Join-Path $licenseDirectory 'android-sdk-license'
$accepted = @($fingerprint)
if (Test-Path -LiteralPath $licensePath) { $accepted += Get-Content -LiteralPath $licensePath }
[IO.File]::WriteAllText($licensePath, (($accepted | Select-Object -Unique) -join "`n") + "`n", (New-Object Text.UTF8Encoding($false)))
$propertiesPath = Join-Path $projectRoot 'local.properties'
if (-not (Test-Path -LiteralPath $propertiesPath)) {
    [IO.File]::WriteAllText($propertiesPath, 'sdk.dir=' + $sdkRoot.Replace('\','/') + "`n", (New-Object Text.UTF8Encoding($false)))
}
Write-Output "SDK ready: $sdkRoot"
