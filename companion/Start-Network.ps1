[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ForgeRoot,
    [ValidateRange(1,65535)][int]$ForgePort = 7860,
    [ValidateRange(1,65535)][int]$CompanionPort = 17872,
    [ValidateRange(1,65535)][int]$Port = 17873,
    [string]$LanAddress,
    [switch]$NoLan, [switch]$NoTailscale,
    [switch]$Pair, [switch]$NoOpen, [switch]$ListDevices, [string]$Revoke,
    [string]$Python, [string]$StateDir,
    [string]$DataRoot, [string]$Config, [string[]]$LoraRoot = @(),
    [string]$TagExtension, [string]$ArExtension
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $ForgeRoot).Path
if (!$Python) {
    $Python = @('venv\Scripts\python.exe','.venv\Scripts\python.exe') |
        ForEach-Object { Join-Path $root $_ } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if (!$Python) { throw 'Pass -Python with the Python executable used by this Forge installation.' }
}
$executable = (Get-Command $Python -CommandType Application -ErrorAction Stop).Source
& $executable -B -c "import cryptography"
if ($LASTEXITCODE -ne 0) { throw 'Install cryptography into the Python environment selected above, then start again.' }
$entry = Join-Path $PSScriptRoot 'forge_remote_gateway.py'
$arguments = @('-B', $entry, '--root', $root, '--forge-port', "$ForgePort", '--companion-port', "$CompanionPort", '--port', "$Port")
if ($LanAddress) { $arguments += @('--lan-address',$LanAddress) }
if ($NoLan) { $arguments += '--no-lan' }
if ($NoTailscale) { $arguments += '--no-tailscale' }
if ($Pair) { $arguments += '--pair' }
if ($NoOpen) { $arguments += '--no-open' }
if ($ListDevices) { $arguments += '--list-devices' }
if ($Revoke) { $arguments += @('--revoke',$Revoke) }
if ($StateDir) { $arguments += @('--state-dir',$StateDir) }
if ($DataRoot) { $arguments += @('--data-root',$DataRoot) }
if ($Config) { $arguments += @('--config',$Config) }
if ($TagExtension) { $arguments += @('--tag-extension',$TagExtension) }
if ($ArExtension) { $arguments += @('--ar-extension',$ArExtension) }
foreach ($item in $LoraRoot) { $arguments += @('--lora-root',$item) }
& $executable @arguments
exit $LASTEXITCODE
