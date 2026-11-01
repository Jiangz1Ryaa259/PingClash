$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$env:GOPATH=Join-Path $projectRoot '.tools\gopath'
$env:GOMODCACHE=Join-Path $projectRoot '.tools\go-mod'
$env:GOCACHE=Join-Path $projectRoot '.tools\go-cache'
$env:GOTOOLCHAIN='local'
$assets=Join-Path $projectRoot 'app\src\main\assets\licenses'
New-Item -ItemType Directory -Path $assets -Force|Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE') -Destination (Join-Path $assets 'GPL-3.0.txt') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'gradle\wrapper\LICENSE-Gradle.txt') -Destination (Join-Path $assets 'Apache-2.0.txt') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot '.tools\go\LICENSE') -Destination (Join-Path $assets 'Go-BSD.txt') -Force
$priorGoOs=$env:GOOS;$priorGoArch=$env:GOARCH;$priorCgo=$env:CGO_ENABLED;$priorCC=$env:CC
Push-Location (Join-Path $projectRoot 'core')
try{
    $env:GOOS='android';$env:GOARCH='arm64';$env:CGO_ENABLED='1'
    $env:CC=Join-Path $projectRoot '.tools\android-sdk\ndk\27.2.12479018\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang.cmd'
    $template='{{if .Module}}{{.Module.Path}}|{{.Module.Version}}|{{.Module.Dir}}{{end}}'
    $modules=& (Join-Path $projectRoot '.tools\go\bin\go.exe') list -mod=readonly -deps -f $template ./mobile
    if($LASTEXITCODE -ne 0){throw 'Android dependency notice collection failed.'}
}finally{
    Pop-Location;$env:GOOS=$priorGoOs;$env:GOARCH=$priorGoArch;$env:CGO_ENABLED=$priorCgo;$env:CC=$priorCC
}
$lines=New-Object 'System.Collections.Generic.List[string]'
$lines.Add('# PingClash dependency notices')
$lines.Add('')
$lines.Add('Standard terminal: Termux terminal-emulator and terminal-view v0.118.3, GPL-3.0. Vendored source, license and local patches accompany the release.')
Copy-Item -LiteralPath (Join-Path $projectRoot 'terminal-emulator\LICENSE.md') -Destination (Join-Path $assets 'Termux-GPL-3.0.txt') -Force
$lines.Add('Proxy page layout and interactions: FlClash (chen08209), GPL-3.0, commit 8d4f9744948751f1273fe871e1d05276fac09b0e; Kotlin/Compose adaptation. No upstream fonts or image assets bundled.')
$lines.Add('Application source: GPL-3.0-only. Engine: MetaCubeX/mihomo v1.19.32. Each dependency retains its own license.')
$lines.Add('Offline Lanyard renderer: user-supplied React Bits component, MIT + Commons Clause; see lanyard/ReactBits.md. React, React DOM and Three.js: MIT; see lanyard/*.txt. Original renderer source, pinned lockfile and Android adaptations accompany the source tree.')
$lines.Add('AndroidX, Kotlin, kotlinx.coroutines and Gradle: Apache-2.0 notices. Go and its mobile binding runtime: BSD notices.')
$lines.Add('VPN state morphing: AndroidX graphics-shapes 1.0.1, Apache-2.0.')
$lines.Add('Navigation blur: Haze and haze-materials 1.6.8, copyright Christopher Banes and the Haze project contributors, Apache-2.0; https://github.com/chrisbanes/haze/tree/1.6.8.')
$lines.Add('Liquid Glass navigation: Backdrop 1.0.4, copyright 2025 Kyant, Apache-2.0; pinned source module and compatibility changes in third-party/backdrop. Upstream commit cf0f35d8c597c06ea8bd42ff7a48821678d74669.')
Copy-Item -LiteralPath (Join-Path $projectRoot 'third-party\backdrop\LICENSE') -Destination (Join-Path $assets 'Backdrop-Apache-2.0.txt') -Force
$lines.Add('QR image decoding: ZXing Core 3.5.3, Copyright ZXing authors, Apache-2.0. https://github.com/zxing/zxing/tree/zxing-3.5.3/core')
$lines.Add('Offline geographic rule data: MetaCubeX/meta-rules-dat, GPL-3.0 repository; pinned GeoIP.dat and GeoSite.dat assets. Source/build attribution and exact checksums: docs/rule-data.md in the corresponding source archive.')
$lines.Add('ASN.mmdb: unchanged October 2026 DB-IP IP to ASN Lite database, copyright DB-IP.com, CC BY 4.0. https://db-ip.com/db/download/ip-to-asn-lite — https://creativecommons.org/licenses/by/4.0/ . Reduced coverage/accuracy. Exact hashes: docs/rule-data.md in the source archive.')
$lines.Add('Full corresponding Go dependency sources and notices are included in the accompanying source archive.')
$lines.Add('')
foreach($entry in ($modules|Where-Object {$_ -match '\|' -and $_ -notmatch '^dev\.pingclash/core\|'}|Sort-Object -Unique)){
    $parts=$entry.Split('|',3);$name=$parts[0];$version=$parts[1];$directory=$parts[2]
    $slug=($name+'@'+$version)-replace '[^A-Za-z0-9._-]','_'
    $target=Join-Path $assets ('go\'+$slug)
    if(-not(Test-Path -LiteralPath $directory)){throw "Dependency source missing: $name"}
    $notices=Get-ChildItem -LiteralPath $directory -File | Where-Object {$_.Name -match '^(LICENSE|LICENCE|COPYING|NOTICE|COPYRIGHT)(\..*)?$'}
    if($notices){New-Item -ItemType Directory -Path $target -Force|Out-Null;foreach($notice in $notices){Copy-Item -LiteralPath $notice.FullName -Destination (Join-Path $target $notice.Name) -Force}}
    $lines.Add('- '+$name+' '+$version)
}
$mobile=Join-Path $env:GOMODCACHE 'golang.org\x\mobile@v0.0.0-20261011034528-197770444867\LICENSE'
Copy-Item -LiteralPath $mobile -Destination (Join-Path $assets 'Go-mobile-BSD.txt') -Force
[IO.File]::WriteAllText((Join-Path $assets 'NOTICE.md'),($lines -join "`n")+"`n",(New-Object Text.UTF8Encoding($false)))
Write-Output 'APK license texts and Android Go-module notices collected.'
