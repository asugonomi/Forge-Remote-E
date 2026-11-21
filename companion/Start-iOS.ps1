[CmdletBinding()]
param(
    [string]$ForgeRoot, [string]$Python, [ValidateRange(1,65535)][int]$ForgePort,
    [ValidateRange(1,65535)][int]$CompanionPort=17872,
    [ValidateRange(1,65535)][int]$WebPort=17875,
    [ValidateRange(1024,65535)][int]$HttpsPort,
    [string]$StateDir, [switch]$NoOpen, [switch]$AcceptCertificate, [switch]$ServeOnly
)
$ErrorActionPreference='Stop'
try {
    # Dot-sourcing the shared folder picker also evaluates its param block.
    $inputsForForge=@($ForgeRoot,$Python,$ForgePort)
    . (Join-Path $PSScriptRoot 'Open-ForgeRemote.ps1')
    $record=Get-ForgeLauncherSettings @inputsForForge
    if (!$record) { exit 0 }
    Write-Host 'Forge Remote E · iPhone / iPad'
    Write-Host 'PCとiPhoneでTailscaleをオンにしてください。ブラウザーに登録用QRが表示されます。'
    $argsForIos=@('-B','-u',(Join-Path $PSScriptRoot 'ios_setup.py'),'--root',$record.root,
        '--forge-port',([string]$record.port),'--companion-port',([string]$CompanionPort),
        '--web-port',([string]$WebPort))
    if ($HttpsPort) { $argsForIos+=@('--https-port',([string]$HttpsPort)) }
    if ($StateDir) { $argsForIos+=@('--state-dir',$StateDir) }
    if ($NoOpen) { $argsForIos+='--no-open' }
    if ($AcceptCertificate) { $argsForIos+='--accept-certificate' }
    if ($ServeOnly) { $argsForIos+='--serve-only' }
    & $record.python @argsForIos
    exit $LASTEXITCODE
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
