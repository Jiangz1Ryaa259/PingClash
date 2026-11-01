$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$compilerRoot=Join-Path $projectRoot '.tools\android-sdk\ndk\27.2.12479018\toolchains\llvm\prebuilt\windows-x86_64\bin'
foreach($target in @(@('arm64-v8a','aarch64-linux-android29-clang.cmd'),@('x86_64','x86_64-linux-android29-clang.cmd'))) {
    $output=Join-Path $projectRoot ('.tools\terminal-native\'+$target[0])
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    & (Join-Path $compilerRoot $target[1]) -shared -fPIC -O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2 '-Wl,-z,relro,-z,now,-z,max-page-size=16384' -o (Join-Path $output 'libtermux.so') (Join-Path $projectRoot 'terminal-emulator\src\main\jni\termux.c')
    if($LASTEXITCODE -ne 0){throw 'Terminal JNI build failed'}
}
