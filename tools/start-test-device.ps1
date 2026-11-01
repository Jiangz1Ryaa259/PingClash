$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$env:ANDROID_HOME=Join-Path $projectRoot '.tools\android-sdk'
$env:ANDROID_USER_HOME=Join-Path $projectRoot '.tools\android-user'
$env:ANDROID_AVD_HOME=Join-Path $projectRoot '.tools\avd'
$env:TEMP=Join-Path $projectRoot '.tools\tmp'
$env:TMP=$env:TEMP
$name='PingClash_API35'
$device=Join-Path $env:ANDROID_AVD_HOME ($name+'.avd')
$image=Join-Path $env:ANDROID_HOME 'system-images\android-35\default\x86_64'
if(-not(Test-Path -LiteralPath (Join-Path $image 'system.img'))){throw 'Install the Android 35 AOSP x86_64 image first.'}
New-Item -ItemType Directory -Path $device -Force|Out-Null
$configuration=@"
AvdId=$name
avd.ini.encoding=UTF-8
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=2048
vm.heapSize=256
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.gpu.enabled=yes
hw.gpu.mode=lavapipe
disk.dataPartition.size=4G
image.sysdir.1=$image\
tag.id=default
tag.display=Default
target=android-35
showDeviceFrame=no
fastboot.forceColdBoot=yes
"@
[IO.File]::WriteAllText((Join-Path $device 'config.ini'),$configuration,[Text.Encoding]::ASCII)
[IO.File]::WriteAllText((Join-Path $env:ANDROID_AVD_HOME ($name+'.ini')),"avd.ini.encoding=UTF-8`npath=$device`ntarget=android-35`n",[Text.Encoding]::ASCII)
$adb=Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adb start-server
$serial='emulator-5556'
$inventory=& $adb devices
if(($inventory -join "`n") -notmatch "$serial\s+(device|offline)"){
    $emulator=Join-Path $projectRoot '.tools\emulator-37.2.12\emulator\emulator.exe'
    if(-not(Test-Path -LiteralPath $emulator)){$emulator=Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'}
    $arguments=@('-avd',$name,'-port','5556','-no-window','-no-audio','-no-snapshot','-no-boot-anim','-gpu','lavapipe','-accel','on','-no-metrics')
    $process=Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $device 'emulator.log') -RedirectStandardError (Join-Path $device 'emulator-error.log')
    Write-Output ('Emulator process: '+$process.Id)
}
Write-Output ('Test serial: '+$serial)
