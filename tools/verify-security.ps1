param([switch]$Fuzz)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:GOPATH = Join-Path $projectRoot '.tools\gopath'
$env:GOMODCACHE = Join-Path $projectRoot '.tools\go-mod'
$env:GOCACHE = Join-Path $projectRoot '.tools\go-cache'
$env:GOTOOLCHAIN = 'local'
$env:GOMAXPROCS = '2'
$env:GOBIN = Join-Path $projectRoot '.tools\bin'
$env:Path = (Join-Path $projectRoot '.tools\go\bin')+';'+$env:Path
$go = Join-Path $projectRoot '.tools\go\bin\go.exe'
$scanner = Join-Path $env:GOBIN 'govulncheck.exe'
$output = Join-Path $projectRoot '.tools\test-results'
New-Item -ItemType Directory -Path $output -Force | Out-Null
Push-Location (Join-Path $projectRoot 'core')
try {
    & $go mod verify
    if ($LASTEXITCODE -ne 0) { throw 'Module checksums failed.' }
    & $go install golang.org/x/vuln/cmd/govulncheck@v1.9.0
    if ($LASTEXITCODE -ne 0) { throw 'Pinned vulnerability scanner installation failed.' }
    if ($Fuzz) {
        & $go test -mod=readonly ./mobile -run '^$' -fuzz '^FuzzUntrustedProfile$' -fuzztime=30s -fuzzminimizetime=1s -parallel=2
        if ($LASTEXITCODE -ne 0) { throw 'Untrusted profile fuzzing failed.' }
        & $go test -mod=readonly ./mobile -run '^$' -fuzz '^FuzzRemoteProviders$' -fuzztime=30s -fuzzminimizetime=1s -parallel=2
        if ($LASTEXITCODE -ne 0) { throw 'Remote providers fuzzing failed.' }
        & $go test -mod=readonly ./mobile -run '^$' -fuzz '^FuzzRuntimeOptions$' -fuzztime=30s -fuzzminimizetime=1s -parallel=2
        if ($LASTEXITCODE -ne 0) { throw 'Runtime options fuzzing failed.' }
        & $go test -mod=readonly ./mobile -run '^$' -fuzz '^FuzzMRSProvider$' -fuzztime=30s -fuzzminimizetime=1s -parallel=2
        if ($LASTEXITCODE -ne 0) { throw 'Binary MRS provider fuzzing failed.' }
    }
    $env:GOOS = 'android'; $env:GOARCH = 'arm64'; $env:CGO_ENABLED = '1'
    $env:CC = Join-Path $projectRoot '.tools\android-sdk\ndk\27.2.12479018\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang.cmd'
    $result = & $scanner -tags=with_gvisor -show verbose ./mobile
    $status = $LASTEXITCODE
    $result | Set-Content -LiteralPath (Join-Path $output 'govulncheck-android.txt') -Encoding UTF8
    $result | Write-Output
    if ($status -ne 0) { throw 'Android dependency vulnerability scan failed.' }
} finally {
    Remove-Item Env:GOOS,Env:GOARCH,Env:CGO_ENABLED,Env:CC -ErrorAction SilentlyContinue
    Pop-Location
}
