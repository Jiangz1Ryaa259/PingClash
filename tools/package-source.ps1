param([switch]$IncludeVendor)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$versionMatch=[regex]::Match([IO.File]::ReadAllText((Join-Path $projectRoot 'app\build.gradle.kts')),'versionName\s*=\s*"([0-9A-Za-z.+-]+)"')
if(-not $versionMatch.Success){throw 'Cannot determine source package version.'}
$packageName='PingClash-'+$versionMatch.Groups[1].Value+'-dev-source'
$allowed=[IO.Path]::GetFullPath((Join-Path $projectRoot '.tools')).TrimEnd('\')+'\'
$stage=[IO.Path]::GetFullPath((Join-Path $projectRoot ('.tools\source-package\'+[guid]::NewGuid().ToString('N'))))
if(-not $stage.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Source staging escaped project tools.'}
$root=Join-Path $stage $packageName
New-Item -ItemType Directory -Path $root -Force|Out-Null
foreach($relative in @('README.md','NOTICE.md','LICENSE','.editorconfig','.gitattributes','.gitignore','settings.gradle.kts','build.gradle.kts','gradle.properties','gradlew','gradlew.bat',
    'app\build.gradle.kts','app\proguard-rules.pro','app\proguard-test-rules.pro','app\proguard-instrumentation.pro',
    'diagnostics\build.gradle.kts','core\go.mod','core\go.sum','core\README.md','core\NOTICE.md','core\LICENSE')){
    $target=Join-Path $root $relative;New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force|Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot $relative) -Destination $target
}
foreach($relative in @('.github','config','app\src','terminal-emulator','terminal-view','third-party','ui\lanyard','diagnostics\src','core\mobile','core\tools','tools','docs','gradle')){
    $source=[IO.Path]::GetFullPath((Join-Path $projectRoot $relative)).TrimEnd('\')+'\'
    foreach($file in (Get-ChildItem -LiteralPath $source -File -Recurse)){
        if($file.FullName.Substring($source.Length) -match '(^|\\)(build|node_modules|__pycache__|\.gradle|\.cxx|\.git)(\\|$)' -or $file.Extension -in @('.pyc','.pyo')){continue}
        $target=Join-Path (Join-Path $root $relative) $file.FullName.Substring($source.Length)
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force|Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $target
    }
}
if($IncludeVendor) {
$vendor=[IO.Path]::GetFullPath((Join-Path $root 'core\vendor'))
if(-not $vendor.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $vendor)){throw 'Vendor destination must be new and inside project tools.'}
$env:GOPATH=Join-Path $projectRoot '.tools\gopath';$env:GOMODCACHE=Join-Path $projectRoot '.tools\go-mod';$env:GOCACHE=Join-Path $projectRoot '.tools\go-cache';$env:GOTOOLCHAIN='local'
Push-Location (Join-Path $projectRoot 'core')
try{& (Join-Path $projectRoot '.tools\go\bin\go.exe') mod vendor -o $vendor;if($LASTEXITCODE -ne 0){throw 'Vendoring source dependencies failed.'}}finally{Pop-Location}
}
$dist=Join-Path $projectRoot 'dist';New-Item -ItemType Directory -Path $dist -Force|Out-Null
$zip=Join-Path $dist ($packageName+'.zip')
if(Test-Path -LiteralPath $zip){$zip=Join-Path $dist ($packageName+'-'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmss')+'.zip')}
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($stage,$zip,[IO.Compression.CompressionLevel]::Optimal,$false)
$digest=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($zip+'.sha256',"$digest  $([IO.Path]::GetFileName($zip))`n",[Text.Encoding]::ASCII)
Write-Output "Source archive: $zip"
Write-Output "SHA256: $digest"
