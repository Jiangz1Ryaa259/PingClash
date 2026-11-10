param([switch]$SkipBuild, [switch]$Release, [switch]$Visuals)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$env:ANDROID_HOME=Join-Path $projectRoot '.tools\android-sdk'
$env:ANDROID_USER_HOME=Join-Path $projectRoot '.tools\android-user'
$env:GRADLE_USER_HOME=Join-Path $projectRoot '.tools\gradle'
if(-not $SkipBuild){
    if($Release){& (Join-Path $PSScriptRoot 'build-release.ps1')}
    else{& (Join-Path $PSScriptRoot 'build-android.ps1')}
    Push-Location $projectRoot
    try{
        if($Release){& .\gradlew.bat --no-daemon --max-workers=2 -PpingclashTestBuildType=release :app:assembleReleaseAndroidTest}
        else{& .\gradlew.bat --no-daemon --max-workers=2 :app:assembleDebugAndroidTest}
        if($LASTEXITCODE -ne 0){throw 'Test APK build failed.'}
    }finally{Pop-Location}
}
& (Join-Path $PSScriptRoot 'start-test-device.ps1')
$adb=Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$serial='emulator-5556'
$booted=$false
$deadline=[DateTime]::UtcNow.AddMinutes(4)
while([DateTime]::UtcNow -lt $deadline){
    $status=& $adb -s $serial shell getprop sys.boot_completed 2>$null
    if($status -match '^1\s*$'){$booted=$true;break}
    Start-Sleep -Seconds 2
}
if(-not $booted){throw 'Project Android test emulator did not boot; inspect .tools/avd/PingClash_API35.avd logs.'}
$avdName=& $adb -s $serial emu avd name
if(($avdName -join "`n") -notmatch '(?m)^PingClash_API35\s*$'){throw 'Refusing to reset data on an unrelated emulator.'}
$appApk=Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk=Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
if($Release){
    $metadata=Get-Content -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\release\output-metadata.json') -Raw | ConvertFrom-Json
    $version=$metadata.elements[0].versionName
    if($version -notmatch '\A[0-9A-Za-z.+-]{1,60}\z'){throw 'Invalid release version name'}
    $appApk=Join-Path $projectRoot ('dist\PingClash-'+$version+'.apk')
    # Distribution is arm64-only. Run the same optimized release code using
    # its universal companion on the dedicated x86_64 emulator.
    $universal=@($metadata.elements | Where-Object {$_.filters.Count -eq 0})
    if($universal.Count -ne 1){throw 'Missing universal release test artifact.'}
    $unsignedApp=Join-Path $projectRoot ('app\build\outputs\apk\release\'+$universal[0].outputFile)
    $signedApp=Join-Path $projectRoot '.tools\native\release-test-app.apk'
    $testApk=Join-Path $projectRoot 'app\build\outputs\apk\androidTest\release\app-release-androidTest.apk'
    $signedTest=Join-Path $projectRoot '.tools\native\app-release-test.apk'
    $key=Join-Path $projectRoot '.tools\signing\pingclash-release.p12'
    $password=Join-Path $projectRoot '.tools\signing\store-password.txt'
    & (Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\apksigner.bat') sign --ks $key --ks-key-alias pingclash-release --ks-pass ("file:"+$password) `
        --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $signedApp $unsignedApp
    if($LASTEXITCODE -ne 0){throw 'Universal release test signing failed.'}
    $appApk=$signedApp
    & (Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\apksigner.bat') sign --ks $key --ks-key-alias pingclash-release --ks-pass ("file:"+$password) `
        --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $signedTest $testApk
    if($LASTEXITCODE -ne 0){throw 'Release instrumentation signing failed.'}
    $testApk=$signedTest
}
# Both variant switches and ordinary reruns reset only the verified project AVD.
& $adb -s $serial uninstall dev.pingclash.app.test 2>$null | Out-Null
& $adb -s $serial uninstall dev.pingclash.app 2>$null | Out-Null
& $adb -s $serial shell settings put global private_dns_mode off
& $adb -s $serial shell settings put global window_animation_scale 0
& $adb -s $serial shell settings put global transition_animation_scale 0
& $adb -s $serial shell settings put global animator_duration_scale 0
& $adb -s $serial shell settings put system font_scale 1.0
& $adb -s $serial shell cmd uimode night no
& $adb -s $serial shell input keyevent 82
& $adb -s $serial shell am force-stop com.android.documentsui
& $adb -s $serial shell am force-stop dev.pingclash.app
& $adb -s $serial shell am force-stop dev.pingclash.app.test
& $adb -s $serial shell input keyevent 3
& $adb -s $serial install -r $appApk
if($LASTEXITCODE -ne 0){throw 'App installation failed.'}
& $adb -s $serial install -r $testApk
if($LASTEXITCODE -ne 0){throw 'Test installation failed.'}
# This serial and AVD are dedicated to synthetic test data, never a user's phone.
& $adb -s $serial shell pm clear dev.pingclash.app
if($LASTEXITCODE -ne 0){throw 'Test fixture reset failed.'}
& $adb -s $serial shell cmd locale set-app-locales dev.pingclash.app --user 0 --locales zh-CN
$output=Join-Path $projectRoot '.tools\test-results'
New-Item -ItemType Directory -Path $output -Force|Out-Null
# Desktop-only self-signed QUIC fixture: loopback and synthetic destinations only.
$env:GOPATH=Join-Path $projectRoot '.tools\gopath'
$env:GOMODCACHE=Join-Path $projectRoot '.tools\go-mod'
$env:GOCACHE=Join-Path $projectRoot '.tools\go-cache'
$env:GOTOOLCHAIN='local'
$fixtureExecutable=Join-Path $projectRoot '.tools\native\hyfixture.exe'
Push-Location (Join-Path $projectRoot 'core')
try {
    & (Join-Path $projectRoot '.tools\go\bin\go.exe') build -p=2 -mod=readonly -trimpath -ldflags '-s -w' -o $fixtureExecutable ./tools/hyfixture
    if($LASTEXITCODE -ne 0){throw 'Hysteria host fixture build failed.'}
} finally {Pop-Location}
$fixtureId=[guid]::NewGuid().ToString('N')
$fixtureReady=Join-Path $output ($fixtureId+'-ready.json')
$fixtureStop=Join-Path $output ($fixtureId+'-stop')
$fixture=Start-Process -FilePath $fixtureExecutable -ArgumentList @('-ready',$fixtureReady,'-stop',$fixtureStop) -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $output 'hyfixture-host.log') -RedirectStandardError (Join-Path $output 'hyfixture-host-error.log')
try {
    $fixtureDeadline=[DateTime]::UtcNow.AddSeconds(15)
    while(!(Test-Path -LiteralPath $fixtureReady)) {
        if($fixture.HasExited -or [DateTime]::UtcNow -gt $fixtureDeadline){throw 'Hysteria host fixture did not start.'}
        Start-Sleep -Milliseconds 100
    }
    $fixtureData=Get-Content -LiteralPath $fixtureReady -Raw | ConvertFrom-Json
    $result=& $adb -s $serial shell am instrument -w -r -e hy2Port $fixtureData.hy2Port -e hy2HttpPort $fixtureData.httpPort `
        -e hy2DnsPort $fixtureData.dnsPort -e hy2Host $fixtureData.host dev.pingclash.app.test/dev.pingclash.app.PingClashTestRunner
    $fixtureStats=Invoke-RestMethod -Uri ('http://127.0.0.1:'+$fixtureData.httpPort+'/stats') -NoProxy -TimeoutSec 3
    $fixtureStats | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'hyfixture-stats.json') -Encoding UTF8
} finally {
    [IO.File]::WriteAllText($fixtureStop,'stop')
    if(!$fixture.WaitForExit(10000)){Stop-Process -Id $fixture.Id -Force}
}
$resultName=if($Release){'android-instrumentation-release.txt'}else{'android-instrumentation.txt'}
$result | Set-Content -LiteralPath (Join-Path $output $resultName) -Encoding UTF8
$result | Write-Output
if(($result -join "`n") -notmatch 'OK \([1-9][0-9]* tests?\)'){
    & $adb -s $serial shell uiautomator dump /sdcard/pingclash-test-failure.xml
    & $adb -s $serial pull /sdcard/pingclash-test-failure.xml (Join-Path $output 'failure-window.xml')
    throw 'Android integration verification failed; inspect test-results.'
}
if($fixtureStats.peerRequests -lt 1 -or $fixtureStats.dnsQueries -lt 1){throw 'Hysteria VPN did not relay peer HTTP and DNS through the selected node.'}
if($Visuals){
    foreach($file in @('ocean-home-light.png','ocean-home-dark.png','ocean-home-large.png','connection-light.png','nodes-light.png','node-policy-light.png','strategy-groups.png','proxy-grid-light.png','proxy-group-region.png','proxy-grid-dark.png','proxy-grid-large.png','certificate-notice.png','import-error-light.png','settings-light.png','connection-dark.png','settings-dark-large.png','settings-dns.png','settings-english-dark.png','settings-live.png','minimal-home-light.png','group-availability.png','delay-compatibility.png')){
        & $adb -s $serial pull ('/sdcard/Android/data/dev.pingclash.app/files/'+$file) (Join-Path $output $file)
        if($LASTEXITCODE -ne 0){throw 'Synthetic visual capture missing.'}
    }
}
Write-Output 'Ordinary and masked-input windows allow capture; plaintext import windows use FLAG_SECURE.'
if($Release){
    $runAs=& $adb -s $serial shell run-as dev.pingclash.app id 2>&1
    if($LASTEXITCODE -eq 0 -or ($runAs -join "`n") -notmatch 'not debuggable'){throw 'Release APK unexpectedly permits run-as.'}
    Write-Output 'The signed universal companion from the same optimized release build passed; shell run-as is denied.'
}
Write-Output 'Android native/VPN/UI verification passed on the project emulator.'
# The expected run-as denial must not become the enclosing PowerShell process's
# exit status after all assertions have passed.
$global:LASTEXITCODE=0
