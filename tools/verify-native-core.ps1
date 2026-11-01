$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$goExecutable = Join-Path $projectRoot '.tools\go\bin\go.exe'
if (-not (Test-Path -LiteralPath $goExecutable)) { $goExecutable = (Get-Command go -ErrorAction Stop).Source }
$env:GOPATH = Join-Path $projectRoot '.tools\gopath'
$env:GOMODCACHE = Join-Path $projectRoot '.tools\go-mod'
$env:GOCACHE = Join-Path $projectRoot '.tools\go-cache'
$env:GOTOOLCHAIN = 'local'
Push-Location (Join-Path $projectRoot 'core')
try {
    $moduleDirectory=& $goExecutable list -mod=readonly -m -f '{{.Dir}}' github.com/metacubex/mihomo
    if ($LASTEXITCODE -ne 0) { throw 'Pinned Mihomo module lookup failed.' }
    python (Join-Path $projectRoot 'tools/audit-mihomo-compatibility.py') --module-dir $moduleDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Mihomo compatibility contract drifted.' }
    & $goExecutable test -tags=with_gvisor -p=2 -mod=readonly ./mobile -count=1 -timeout=120s
    if ($LASTEXITCODE -ne 0) { throw 'Native core verification failed.' }
} finally { Pop-Location }
