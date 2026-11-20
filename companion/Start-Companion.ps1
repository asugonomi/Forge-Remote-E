[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ForgeRoot,
    [ValidateRange(1,65535)][int]$ForgePort = 7860,
    [ValidateRange(1,65535)][int]$Port = 17872,
    [string]$Python,
    [string]$DataRoot,
    [string]$Config,
    [string[]]$LoraRoot = @(),
    [string]$TagExtension,
    [string]$ArExtension
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
$entry = Join-Path $PSScriptRoot 'forge_remote_api.py'
$arguments = @('-B', $entry, '--root', $root, '--forge-port', "$ForgePort", '--port', "$Port")
if ($DataRoot) { $arguments += @('--data-root',$DataRoot) }
if ($Config) { $arguments += @('--config',$Config) }
if ($TagExtension) { $arguments += @('--tag-extension',$TagExtension) }
if ($ArExtension) { $arguments += @('--ar-extension',$ArExtension) }
foreach ($item in $LoraRoot) { $arguments += @('--lora-root',$item) }
& $executable @arguments
exit $LASTEXITCODE
