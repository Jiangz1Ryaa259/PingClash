param([Parameter(Mandatory=$true)][string]$SourceUri,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][long]$ExpectedSize)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$allowed=[IO.Path]::GetFullPath((Join-Path $projectRoot '.tools\downloads')).TrimEnd('\')+'\'
$destination=[IO.Path]::GetFullPath($OutputPath)
if(-not $destination.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Download must stay inside project downloads.'}
$uri=[uri]$SourceUri
if($uri.Scheme -ne 'https' -or $uri.Host -notin @('dl.google.com','redirector.gvt1.com')){throw 'Only official Google SDK hosts are allowed.'}
$chunkRoot=$destination+'.chunks'
New-Item -ItemType Directory -Path $chunkRoot -Force|Out-Null
$chunkSize=16MB
$count=[int][Math]::Ceiling($ExpectedSize/[double]$chunkSize)
$pending=New-Object 'System.Collections.Generic.Queue[int]'
for($index=0;$index -lt $count;$index++){
    $part=Join-Path $chunkRoot ("{0:D4}.part" -f $index)
    $length=[Math]::Min($chunkSize,$ExpectedSize-[long]$index*$chunkSize)
    if(-not(Test-Path -LiteralPath $part) -or (Get-Item -LiteralPath $part).Length -ne $length){$pending.Enqueue($index)}
}
$active=@()
while($pending.Count -gt 0 -or $active.Count -gt 0){
    while($active.Count -lt 4 -and $pending.Count -gt 0){
        $index=$pending.Dequeue();$start=[long]$index*$chunkSize;$end=[Math]::Min($ExpectedSize-1,$start+$chunkSize-1)
        $part=Join-Path $chunkRoot ("{0:D4}.part" -f $index)
        $arguments=@('--fail','--silent','--show-error','--location','--ssl-revoke-best-effort','--connect-timeout','10','--max-time','120',
            '--speed-time','20','--speed-limit','4096','--retry','2','--retry-delay','1','--range',"$start-$end",'--output',('"'+$part+'"'),('"'+$SourceUri+'"'))
        $process=Start-Process -FilePath curl.exe -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardError ($part+'.log')
        $active+=@{Process=$process;Index=$index;Path=$part;Size=$end-$start+1}
    }
    Start-Sleep -Milliseconds 500
    $remaining=@()
    foreach($task in $active){
        $task.Process.Refresh()
        if(-not $task.Process.HasExited){$remaining+=$task;continue}
        if(-not(Test-Path -LiteralPath $task.Path) -or (Get-Item -LiteralPath $task.Path).Length -ne $task.Size){
            throw ('SDK chunk failed: '+$task.Index+'; inspect '+$task.Path+'.log')
        }
        Write-Output ('Downloaded chunk '+($task.Index+1)+'/'+$count)
    }
    $active=$remaining
}
$output=[IO.File]::Create($destination)
try{for($index=0;$index -lt $count;$index++){
    $input=[IO.File]::OpenRead((Join-Path $chunkRoot ("{0:D4}.part" -f $index)))
    try{$input.CopyTo($output)}finally{$input.Dispose()}
}}finally{$output.Dispose()}
if((Get-Item -LiteralPath $destination).Length -ne $ExpectedSize){throw 'SDK download size mismatch.'}
Write-Output 'Archive assembled; run the SDK installer to verify its official checksum.'
