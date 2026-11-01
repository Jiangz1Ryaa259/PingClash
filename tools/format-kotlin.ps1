param([switch]$Check)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$jar=Join-Path $projectRoot '.tools\ktfmt-0.59.jar'
$expected='a493271c137074436a133d72e9e323f91604c97e0c40555629ec39598efead54'
if(-not(Test-Path -LiteralPath $jar)) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $jar) -Force|Out-Null
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/com/facebook/ktfmt/0.59/ktfmt-0.59-with-dependencies.jar' -OutFile $jar
}
if((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $expected){throw 'Formatter checksum mismatch.'}
$java=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin\java.exe'}else{(Get-Command java).Source}
$arguments=@('--kotlinlang-style')
if($Check){$arguments+=@('--dry-run','--set-exit-if-changed')}
foreach($folder in @('app\src','diagnostics\src')) {
    $arguments+=@(Get-ChildItem -LiteralPath (Join-Path $projectRoot $folder) -Filter '*.kt' -File -Recurse|ForEach-Object {$_.FullName})
}
& $java -jar $jar @arguments
if($LASTEXITCODE -ne 0){throw 'Kotlin formatting check failed.'}
