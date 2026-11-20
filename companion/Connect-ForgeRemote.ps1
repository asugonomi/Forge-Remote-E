[CmdletBinding()]
param(
    [string]$Adb = 'adb',
    [ValidateRange(1,65535)][int]$ServerPort = 5037,
    [ValidateRange(1,65535)][int]$ForgePort = 7860,
    [ValidateRange(1,65535)][int]$CompanionPort = 17872,
    [string]$Device
)
$ErrorActionPreference = 'Stop'
$tool = (Get-Command $Adb -CommandType Application -ErrorAction Stop).Source
function Invoke-Adb([string[]]$Arguments) {
    $lines = @(& $tool '-P' "$ServerPort" @Arguments)
    if ($LASTEXITCODE) { throw "Android Platform Tools reported an error ($LASTEXITCODE)." }
    return $lines
}
$list = @(Invoke-Adb @('devices','-l') | ForEach-Object {
    if ($_ -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
})
if ($Device) {
    if ($Device -notin $list) { throw 'The requested serial is not authorized and connected.' }
} elseif ($list.Count -eq 1) {
    $Device = $list[0]
} else {
    throw 'Connect one authorized phone, or choose an exact serial with -Device from adb devices -l.'
}
$target = @('-s',$Device)
$maps = [ordered]@{'tcp:17870'="tcp:$ForgePort"; 'tcp:17872'="tcp:$CompanionPort"}
$existing = @{}
foreach ($line in @(Invoke-Adb ($target+@('reverse','--list')))) {
    if ($line -match '(?:^|\s)(tcp:\d+)\s+(\S+)\s*$') { $existing[$Matches[1]]=$Matches[2] }
}
foreach ($devicePort in $maps.Keys) {
    if ($existing.ContainsKey($devicePort) -and $existing[$devicePort] -ne $maps[$devicePort]) {
        throw "$devicePort is already assigned to another host port on this phone."
    }
}
Invoke-RestMethod -Uri "http://127.0.0.1:$ForgePort/sdapi/v1/samplers" -TimeoutSec 12 | Out-Null
Invoke-RestMethod -Uri "http://127.0.0.1:$CompanionPort/forge-remote/v1/capabilities" -TimeoutSec 12 | Out-Null
$created = @()
try {
    foreach ($devicePort in $maps.Keys) {
        if (!$existing.ContainsKey($devicePort)) {
            Invoke-Adb ($target+@('reverse','--no-rebind',$devicePort,$maps[$devicePort])) | Out-Null
            $created += $devicePort
        }
    }
    $launched = @(Invoke-Adb ($target+@('shell','am','start','-W','-n','io.github.asugonomi.forgeremote.e/jp.local.forgedesk.Desk')))
    if ($launched -match '(^|\s)(Error|Exception)') { throw 'Install the E edition APK on the selected phone.' }
} catch {
    foreach ($devicePort in $created) {
        & $tool '-P' "$ServerPort" @target reverse --remove $devicePort | Out-Null
    }
    throw
}
Write-Output "Forge Remote E is connected on device ports 17870 and 17872."
