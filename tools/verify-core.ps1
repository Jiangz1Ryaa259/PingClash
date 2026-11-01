$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDirectory = Join-Path $projectRoot '.tools\core-verification'
$classDirectory = Join-Path $outputDirectory 'classes'
New-Item -ItemType Directory -Path $classDirectory -Force | Out-Null

$sourceFiles = @(& rg --files (Join-Path $projectRoot 'diagnostics\src\main\java') (Join-Path $projectRoot 'diagnostics\src\test\java') -g '*.java')
& javac -encoding UTF-8 --release 17 -d $classDirectory @sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'Core compilation failed.' }

# This certificate and password are used only by local tests; never by the app.
$keyStore = Join-Path $outputDirectory ('localhost-' + [guid]::NewGuid().ToString('N') + '.p12')
try {
    & keytool -genkeypair -alias localhost -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=localhost' -ext 'SAN=dns:localhost' -storetype PKCS12 -keystore $keyStore -storepass test-only -keypass test-only -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Test certificate generation failed.' }
    & java '-Dfile.encoding=UTF-8' -cp $classDirectory dev.pingclash.diagnostics.CoreVerification $keyStore (Join-Path $projectRoot 'app\src\main\res\values-zh-rCN\diagnostics.xml')
    if ($LASTEXITCODE -ne 0) { throw 'Core verification failed.' }
} finally {
    # Delete only the exact temporary file created above, inside our test output folder.
    if (Test-Path -LiteralPath $keyStore) { Remove-Item -LiteralPath $keyStore }
}
