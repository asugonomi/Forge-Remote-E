[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)][string]$ForgeRoot,
    [string]$DataRoot,
    [switch]$Update
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $ForgeRoot).Path
if (!(Test-Path -LiteralPath (Join-Path $root 'launch.py') -PathType Leaf)) { throw 'Forge root must contain launch.py.' }
$data = if ($DataRoot) { (Resolve-Path -LiteralPath $DataRoot).Path } else { $root }
$extensions = Join-Path $data 'extensions'
if (!(Test-Path -LiteralPath $extensions -PathType Container)) { throw 'Forge extensions directory is missing. Check -DataRoot for a custom --data-dir.' }
$name = 'forge-remote-job-control'
$source = Join-Path $PSScriptRoot "$name\scripts\forge_job_control.py"
$dedicated = Join-Path $extensions $name
$scripts = Join-Path $dedicated 'scripts'
$installed = Join-Path $scripts 'forge_job_control.py'
$incoming = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
if (Test-Path -LiteralPath $installed) {
    if ((Get-FileHash -LiteralPath $installed -Algorithm SHA256).Hash -eq $incoming) {
        Write-Output 'Job control is already installed.'
        return
    }
    if (!$Update) { throw 'A different job-control script exists. Review the file and pass -Update to keep a backup and replace it.' }
}
if (!$PSCmdlet.ShouldProcess($installed, 'Install scoped job control')) { return }
[void][IO.Directory]::CreateDirectory($scripts)
$staged = Join-Path $scripts ([guid]::NewGuid().ToString('N') + '.tmp')
try {
    [IO.File]::Copy($source,$staged)
    if (Test-Path -LiteralPath $installed) {
        $backup = Join-Path $dedicated ('forge_job_control.' + [guid]::NewGuid().ToString('N') + '.bak')
        [IO.File]::Replace($staged,$installed,$backup)
        Write-Output "Previous job-control script backed up to $backup"
    } else {
        [IO.File]::Move($staged,$installed)
    }
} finally {
    if (Test-Path -LiteralPath $staged) { [IO.File]::Delete($staged) }
}
if ((Get-FileHash -LiteralPath $installed -Algorithm SHA256).Hash -ne $incoming) { throw 'Installed file differs from source.' }
Write-Output 'Installed. When the Forge queue is idle, use Settings > Reload UI or restart Forge.'
