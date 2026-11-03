$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'build-native.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Native release library build failed.' }
& (Join-Path $PSScriptRoot 'collect-notices.ps1')
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-user'
$signing = Join-Path $projectRoot '.tools\signing'
New-Item -ItemType Directory -Path $signing -Force | Out-Null
# A private, project-local signing identity. Never use the public Android debug
# key for an APK intended for distribution, or include this folder in source ZIPs.
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = Get-Acl -LiteralPath $signing
$systemIdentity = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
$rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
$restricted = $acl.AreAccessRulesProtected -and $rules.Count -eq 2 -and @($rules | Where-Object {
    $_.IdentityReference -notin @($identity, $systemIdentity) -or $_.IsInherited -or
    $_.AccessControlType -ne 'Allow' -or $_.FileSystemRights -ne 'FullControl'
}).Count -eq 0
$inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
if (!$restricted) {
    # Change only the DACL; constructing a new security descriptor and replacing
    # its owner can unnecessarily demand SeSecurityPrivilege on a repeat build.
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($rule in $acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier])) { $acl.RemoveAccessRuleSpecific($rule) }
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', $inheritance, 'None', 'Allow'))
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($systemIdentity, 'FullControl', $inheritance, 'None', 'Allow'))
    Set-Acl -LiteralPath $signing -AclObject $acl
}
$key = Join-Path $signing 'pingclash-release.p12'
$passwordFile = Join-Path $signing 'store-password.txt'
if ((Test-Path -LiteralPath $key) -and !(Test-Path -LiteralPath $passwordFile)) { throw 'Signing key password is missing; preserve the key and restore its password.' }
if (!(Test-Path -LiteralPath $passwordFile)) {
    $random = [byte[]]::new(32)
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($random) } finally { $generator.Dispose() }
    [IO.File]::WriteAllText($passwordFile, [BitConverter]::ToString($random).Replace('-', ''), [Text.Encoding]::ASCII)
}
$env:PINGCLASH_SIGNING_PASSWORD = [IO.File]::ReadAllText($passwordFile)
try {
    if (!(Test-Path -LiteralPath $key)) {
        & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -noprompt -keystore $key -storetype PKCS12 -alias pingclash-release `
            -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=PingClash Local Release' -storepass:env PINGCLASH_SIGNING_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Signing identity generation failed.' }
    }
} finally { Remove-Item Env:PINGCLASH_SIGNING_PASSWORD -ErrorAction SilentlyContinue }
Push-Location $projectRoot
try {
    & .\gradlew.bat --no-daemon --max-workers=2 -PpingclashSplitApks=true detekt :app:assembleRelease :app:lintRelease
    if ($LASTEXITCODE -ne 0) { throw 'Release compilation or lint failed.' }
    # ABI splits write per-ABI copies; the main merged manifest is ABI-independent.
    $manifestPath = @('merged_manifest\release\processReleaseMainManifest', 'merged_manifests\release\processReleaseManifest') |
        ForEach-Object { Join-Path $projectRoot ('app\build\intermediates\' + $_ + '\AndroidManifest.xml') } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ($null -eq $manifestPath) { throw 'Merged release manifest not found.' }
    [xml]$manifest = Get-Content -LiteralPath $manifestPath -Raw
    $androidNamespace = 'http://schemas.android.com/apk/res/android'
    # System-facing components are allowed only with the protection that keeps other apps out:
    # the tile is bindable by the system alone, the boot receiver takes only the protected boot
    # broadcast, and the widget provider only redraws (its switch is a non-exported receiver).
    $guarded = @{
        'dev.pingclash.app.ConnectTileService' = { param($c) $c.GetAttribute('permission', $androidNamespace) -eq 'android.permission.BIND_QUICK_SETTINGS_TILE' }
        'dev.pingclash.app.BootReceiver' = { param($c) @($c.'intent-filter'.action | ForEach-Object { $_.GetAttribute('name', $androidNamespace) }) -join ',' -eq 'android.intent.action.BOOT_COMPLETED' }
        'dev.pingclash.app.ConnectWidget' = { param($c) @($c.'intent-filter'.action | ForEach-Object { $_.GetAttribute('name', $androidNamespace) }) -join ',' -eq 'android.appwidget.action.APPWIDGET_UPDATE' }
    }
    foreach ($component in $manifest.manifest.application.ChildNodes) {
        if ($component -isnot [Xml.XmlElement] -or $component.GetAttribute('exported', $androidNamespace) -ne 'true') { continue }
        $name = $component.GetAttribute('name', $androidNamespace)
        if ($name -in @('dev.pingclash.app.MainActivity','dev.pingclash.app.SubscriptionImportActivity')) { continue }
        if ($guarded.ContainsKey($name) -and (& $guarded[$name] $component)) { continue }
        throw ('Unexpected exported release component ' + $name + '; review before signing.')
    }
    $buildTools = Join-Path $projectRoot '.tools\android-sdk\build-tools\35.0.0'
    $dist = Join-Path $projectRoot 'dist'
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    $metadata=Get-Content -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\release\output-metadata.json') -Raw | ConvertFrom-Json
    $artifact=@($metadata.elements | Where-Object {@($_.filters | Where-Object {$_.filterType -eq 'ABI' -and $_.value -eq 'arm64-v8a'}).Count -gt 0})[0]
    if($null -eq $artifact){throw 'Missing arm64 release artifact'}
    $version=$artifact.versionName
    if($version -notmatch '\A[0-9A-Za-z.+-]{1,60}\z'){throw 'Invalid release version name'}
    $apk=Join-Path $dist ('PingClash-'+$version+'.apk')
    & (Join-Path $buildTools 'apksigner.bat') sign --ks $key --ks-key-alias pingclash-release --ks-pass ("file:"+$passwordFile) `
        --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false `
        --out $apk (Join-Path $projectRoot ('app\build\outputs\apk\release\'+$artifact.outputFile))
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    # A previous v4-enabled build may have left a sidecar for different bytes.
    if (Test-Path -LiteralPath ($apk+'.idsig')) { Remove-Item -LiteralPath ($apk+'.idsig') -Force }
    & (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    & (Join-Path $buildTools 'zipalign.exe') -c -P 16 4 $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK native alignment verification failed.' }
    $badging = & (Join-Path $buildTools 'aapt.exe') dump badging $apk
    if ($LASTEXITCODE -ne 0 -or ($badging -join "`n") -match 'application-debuggable') { throw 'Release APK must not be debuggable.' }
    $digest = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($apk+'.sha256', "$digest  $([IO.Path]::GetFileName($apk))`n", [Text.Encoding]::ASCII)
    Write-Output "Non-debuggable preview APK: $apk"
    Write-Output "SHA256: $digest"
} finally { Pop-Location }
